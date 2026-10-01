"""命名规则与存储实现的单元测试。"""

from __future__ import annotations

import pytest

from packager import Entry, FileRef, LocalStorage, ObjectStorage, StorageError
from packager.naming import dir_name, file_name, pad_no, sanitize


@pytest.mark.parametrize(
    ("no", "expected"),
    [("1", "01"), ("3.1", "03.1"), ("12", "12"), ("A.2", "A.2"), ("10.2.1", "10.2.1")],
)
def test_pad_no(no, expected):
    assert pad_no(no) == expected


def test_sanitize_strips_illegal_chars():
    assert sanitize('审计/报告:2023*"?') == "审计_报告_2023_"
    assert sanitize("  ") == "_"


def test_dir_and_file_name():
    entry = Entry(no="1", std_name="营业执照", period="2024")
    ref = FileRef(uri="oss://docs/x.PDF")
    assert dir_name(entry) == "01_营业执照"
    assert file_name(entry, ref) == "01_营业执照_2024.pdf"
    assert file_name(entry, FileRef(uri="a", period="2025"), 1, 2) == "01_营业执照_2025_2.pdf"


def test_entry_ordered_files_stable():
    entry = Entry(
        no="1",
        std_name="x",
        files=[FileRef(uri="b", order=2), FileRef(uri="a1", order=1), FileRef(uri="a2", order=1)],
    )
    assert [f.uri for f in entry.ordered_files] == ["a1", "a2", "b"]


def test_local_storage_roundtrip(tmp_path):
    st = LocalStorage(tmp_path)
    (tmp_path / "d").mkdir()
    (tmp_path / "d" / "f.pdf").write_bytes(b"x")
    assert st.read("oss://d/f.pdf") == b"x"
    assert st.read("d/f.pdf") == b"x"
    uri = st.write("p/out.bin", b"y")
    with open(uri, "rb") as fh:
        assert fh.read() == b"y"
    assert "expires_in=60" in st.signed_url(uri, expires_in=60)


def test_local_storage_rejects_traversal(tmp_path):
    st = LocalStorage(tmp_path / "root")
    with pytest.raises(StorageError, match="越界"):
        st.read("oss://../secret.pdf")


def test_object_storage_is_stub():
    st = ObjectStorage(bucket="b")
    for call in (lambda: st.read("u"), lambda: st.write("n", b""), lambda: st.signed_url("u")):
        with pytest.raises(NotImplementedError):
            call()
