"""外发水印：在模型生成内容中注入不可见溯源标记。

T16 验收要求："外发水印"。
现有水印只在 packager PDF 打包阶段（PdfBuilder），不在模型回答中。
本模块在 gateway 返回的回答文本中注入零宽字符水印，用于溯源泄露。

水印策略：
1. 在回答文本末尾追加零宽字符序列（ZWSP + ZWNJ 组合编码 user_id）
2. 肉眼不可见，但可通过程序解码还原 user_id
3. 水印仅对 generate 用途的回答启用（embed/rerank 不需要）
"""

from __future__ import annotations

import logging

logger = logging.getLogger("gateway.watermark")

# 零宽字符编码表（4 bit → 2 字符组合）
# ZWSP (U+200B) = 0, ZWNJ (U+200C) = 1
_ZWSP = "\u200b"
_ZWNJ = "\u200c"
_SEPARATOR = "\u200d"  # ZWJ 作为分隔符


def _encode_user_id(user_id: str) -> str:
    """将 user_id 编码为零宽字符序列。"""
    if not user_id:
        return ""
    # 取 user_id 的 UTF-8 字节，每字节 8 bit
    bits = []
    for byte in user_id.encode("utf-8"):
        for i in range(7, -1, -1):
            bits.append((byte >> i) & 1)
    # 每 bit 映射为一个零宽字符
    return "".join(_ZWSP if b == 0 else _ZWNJ for b in bits)


def _decode_watermark(wm: str) -> str:
    """从零宽字符序列解码出 user_id。"""
    if not wm:
        return ""
    # 去掉分隔符
    bits = []
    for ch in wm:
        if ch == _ZWSP:
            bits.append(0)
        elif ch == _ZWNJ:
            bits.append(1)
        elif ch == _SEPARATOR:
            continue
        else:
            continue
    if len(bits) < 8:
        return ""
    # 每 8 bit 组一个字节
    byte_vals = []
    for i in range(0, len(bits) - 7, 8):
        byte_val = 0
        for j in range(8):
            byte_val = (byte_val << 1) | bits[i + j]
        byte_vals.append(byte_val)
    try:
        return bytes(byte_vals).decode("utf-8")
    except (UnicodeDecodeError, ValueError):
        return ""


def apply_watermark(text: str, user_id: str) -> str:
    """在回答文本末尾注入零宽字符水印。

    水印格式：可见文本 + 分隔符 + 零宽编码(user_id)
    肉眼看到的是 ``text`` 本身，零宽字符不可见。

    Args:
        text: 模型回答的可见文本。
        user_id: 要溯源的用户 ID。

    Returns:
        带水印的文本（可见部分不变，末尾追加零宽字符）。
    """
    if not text or not user_id:
        return text
    encoded = _encode_user_id(user_id)
    if not encoded:
        return text
    return text + _SEPARATOR + encoded


def extract_watermark(text: str) -> str | None:
    """从带水印的文本中提取 user_id。

    用于溯源：当回答内容被泄露时，可从文本中提取零宽字符序列，
    解码出原始 user_id。

    Args:
        text: 可能包含水印的文本。

    Returns:
        解码出的 user_id，或 None（无水印或损坏）。
    """
    if not text:
        return None
    # 找到分隔符后的零宽字符序列
    sep_idx = text.rfind(_SEPARATOR)
    if sep_idx == -1:
        return None
    wm_part = text[sep_idx + 1:]
    if not wm_part:
        return None
    decoded = _decode_watermark(wm_part)
    return decoded if decoded else None


def strip_watermark(text: str) -> str:
    """移除文本中的水印（用于显示纯文本）。"""
    if not text:
        return text
    sep_idx = text.rfind(_SEPARATOR)
    if sep_idx == -1:
        return text
    return text[:sep_idx]
