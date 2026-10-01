"""L3 脱敏测试：请求体离开进程前必须已脱敏，返回后还原。"""

from __future__ import annotations

from gateway.config import MaskingConfig
from gateway.masking import EntityMasker
from gateway.schemas import Sensitivity

from .conftest import make_request

SENSITIVE_PROMPT = (
    "请根据航锂新能源科技有限公司与示例客户甲的合同，核对金额 ¥1,200,000 元，"
    "联系人手机号 13800138000，身份证 11010119900307123X。"
)


def test_l3_请求体离开进程前已脱敏(service, factory):
    service.invoke(make_request(sensitivity=Sensitivity.L3, payload={"prompt": SENSITIVE_PROMPT}))
    sent = factory.calls[-1].payload["prompt"]
    for secret in (
        "航锂新能源科技有限公司",
        "示例客户甲",
        "13800138000",
        "11010119900307123X",
        "1,200,000",
    ):
        assert secret not in sent, f"脱敏遗漏：{secret}"
    assert "[[COMPANY_1]]" in sent and "[[PHONE_1]]" in sent


def test_l3_返回后还原占位符(service, factory):
    factory.client.responder = lambda spec, purpose, payload: {"text": payload["prompt"]}
    response = service.invoke(make_request(sensitivity=Sensitivity.L3, payload={"prompt": SENSITIVE_PROMPT}))
    assert response.output["text"] == SENSITIVE_PROMPT


def test_l1_l2_不脱敏(service, factory):
    for level in (Sensitivity.L1, Sensitivity.L2):
        service.invoke(make_request(sensitivity=level, payload={"prompt": SENSITIVE_PROMPT}))
        assert factory.calls[-1].payload["prompt"] == SENSITIVE_PROMPT


def test_嵌套结构与列表都会脱敏(service, factory):
    payload = {
        "messages": [
            {"role": "system", "content": "你是合同助手"},
            {"role": "user", "content": SENSITIVE_PROMPT},
        ],
        "documents": ["示例客户乙的报价 88万元"],
    }
    service.invoke(make_request(sensitivity=Sensitivity.L3, payload=payload))
    sent = factory.calls[-1].payload
    assert "航锂新能源科技有限公司" not in sent["messages"][1]["content"]
    assert "示例客户乙" not in sent["documents"][0]
    assert "88万元" not in sent["documents"][0]


def test_同一实体复用同一占位符():
    masker = EntityMasker(MaskingConfig(literals={"company": ("航锂储能",)}))
    result = masker.mask({"a": "航锂储能今年", "b": "还是航锂储能"})
    assert result.payload["a"] == "[[COMPANY_1]]今年"
    assert result.payload["b"] == "还是[[COMPANY_1]]"
    assert len(result.mapping) == 1
    assert masker.restore(result.payload, result.mapping) == {"a": "航锂储能今年", "b": "还是航锂储能"}


def test_长词优先避免短词先匹配():
    masker = EntityMasker(MaskingConfig(literals={"company": ("航锂", "航锂储能")}))
    result = masker.mask("航锂储能")
    assert result.mapping[result.payload] == "航锂储能"
