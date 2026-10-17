#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
gmsstu（研究生教务）接口闭环实测探针。

用途：在电脑端用**你自己的研究生账号**把「登录 → 学期列表 → 整学期课表 → 本周课表」
整条链路真实跑通，校验 ZZUPocket PR 中记录的协议（docs/gms-schedule-api.md）。

用法：
    set ZZU_GMS_USER=学号        （PowerShell: $env:ZZU_GMS_USER="学号"）
    set ZZU_GMS_PASS=密码
    python tools/gms_probe.py                 # 默认当前学期 + 本周视图
    python tools/gms_probe.py --term 20261 --out out_dir

隐私：
    · 密码只从环境变量或 stdin 读取，不落盘、不进日志；
    · 落盘输出默认【脱敏】：教师姓名 → {教师N}；加 --raw 才输出原文。

依赖：仅标准库。密码加密为 RSA/PKCS#1 v1.5（与浏览器 jsencrypt 等价），
      这里手工实现，不引入第三方依赖。
"""

import argparse
import base64
import getpass
import http.cookiejar
import json
import os
import random
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

GMS_BASE = "https://gmsstu.zzu.edu.cn"
CAS_BASE = "https://cas.s.zzu.edu.cn"
STU_HOME = GMS_BASE + "/stu/"
STU_SERVICE = "https%3A%2F%2Fgmsstu.zzu.edu.cn%2Fstu%2Fcas"
CAS_JWT_PUBKEY = CAS_BASE + "/cas/jwt/publicKey"

ROUTE_APPKEY = "MyHeartWillGoOn"
GUID_RE = re.compile(r"#([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})")
EXECUTION_RES = re.compile(
    r'name="execution"[^>]*value="([^"]+)|value="([^"]+)"[^>]*name="execution"'
)


# ── HTTP（CookieJar + 手动跟随重定向，便于分步取证） ─────────────────────

cookie_jar = http.cookiejar.CookieJar()


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """把 3xx 抛成 HTTPError，好拿到 Status/Location 自己走链。"""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookie_jar))
opener.addheaders = [("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) gms-probe")]
no_redir = urllib.request.build_opener(
    urllib.request.HTTPCookieProcessor(cookie_jar), NoRedirect(),
)


def fetch(url, data=None, headers=None, follow=True):
    req = urllib.request.Request(url, data=data, headers=headers, method="POST" if data is not None else "GET")
    op = opener if follow else no_redir
    try:
        with op.open(req, timeout=30) as r:
            return {
                "status": r.status, "url": r.geturl(),
                "body": r.read().decode("utf-8", "replace"),
                "headers": dict(r.headers.items()), "error": None,
            }
    except urllib.error.HTTPError as e:
        return {
            "status": e.code, "url": e.geturl(),
            "body": (e.read() or b"").decode("utf-8", "replace"),
            "headers": dict(e.headers.items()), "error": None,
        }
    except Exception as e:
        return {"status": None, "url": url, "body": "", "headers": {}, "error": str(e)}


def hop(url, data=None, headers=None, max_hops=8):
    """手动跟随重定向；POST 跳转后转为 GET（浏览器同语义）。返回 (最终响应, 链路)。"""
    history = []
    for _ in range(max_hops):
        r = fetch(url, data, headers, follow=False)
        history.append({"status": r["status"], "url": r["url"], "error": r["error"]})
        if r["status"] in (301, 302, 303, 307, 308):
            loc = r["headers"].get("Location", "")
            if not loc:
                break
            url = urllib.parse.urljoin(r["url"], loc)
            data = None  # 302/303 后按浏览器语义转 GET
            headers = None
            continue
        return r, history
    return r, history


# ── RSA（jsencrypt 等价：X.509 SPKI 公钥 + RSA PKCS#1 v1.5） ─────────────

def pem_to_n_e(pem_text):
    """手工解析 SubjectPublicKeyInfo DER 提取 (n, e)，避免引入 cryptography。"""
    b64 = re.sub(r"\s", "", re.sub(r"-----[A-Z ]+-----", "", pem_text))
    der = base64.b64decode(b64)

    def read_len(d, i):
        l = d[i]; i += 1
        if l & 0x80:
            n = l & 0x7F
            l = int.from_bytes(d[i:i + n], "big"); i += n
        return l, i

    def read_tlv(d, i):
        t = d[i]; i += 1
        l, i = read_len(d, i)
        return t, d[i:i + l], i + l

    _t, _seq, _ = read_tlv(der, 0)              # SPKI SEQ
    _t, _alg, j = read_tlv(_seq, 0)             # AlgorithmIdentifier
    _t, bits, _k = read_tlv(_seq, j)            # BITSTRING
    rsa_der = bits[1:]                          # 首字节为未用位数
    _t, rsa_seq, _l = read_tlv(rsa_der, 0)      # RSAPublicKey SEQ
    _t, _n, k = read_tlv(rsa_seq, 0)            # n（INTEGER，有符号前缀 0x00 已由 read_len 剥离）
    _t, _e, _z = read_tlv(rsa_seq, k)           # e
    if _n and _n[0] == 0:
        _n = _n[1:]
    if _e and _e[0] == 0:
        _e = _e[1:]
    return int.from_bytes(_n, "big"), int.from_bytes(_e, "big")


def rsa_pkcs1_encrypt(plain, n, e):
    """EME-PKCS1-v1_5 编码后 modpow（密码远小于单块容量）。"""
    m = plain.encode("utf-8")
    k = (n.bit_length() + 7) // 8
    if len(m) > k - 11:
        raise ValueError("密码过密，超出单块 RSA 容量")
    pad_len = k - len(m) - 3
    ps = bytes(random.SystemRandom().randrange(1, 256) for _ in range(pad_len))
    em = b"\x00\x02" + ps + b"\x00" + m
    c = pow(int.from_bytes(em, "big"), e, n)
    return c.to_bytes(k, "big")


# ── 路由编码（与实测串比对过的规则） ──────────────────────────────────

def encode_route(module_path, guid):
    plain = f"{ROUTE_APPKEY}{module_path}#{guid}"
    return base64.b64encode(plain.encode("ascii")).decode("ascii").replace("=", "_")


# ── 课表解析（与 Kotlin GmsScheduleParser 逐句对齐的镜像） ─────────────

BRACKET = re.compile(r"\[([^\[\]]+)\]")
CODE_TAIL = re.compile(r"(\d{6,10})_(\d{1,3})\s*$")
CAMPUS_MARK = re.compile(r"(主校区|南校区|北校区|东校区)")
WEEK_RANGE = re.compile(r"(\d+)(?:-(\d+))?周")


def parse_cell(raw):
    if not raw:
        return None
    text = raw.strip()
    if not text:
        return None
    selective = "##" in text
    text = text.replace("##", "").strip()
    if not text:
        return None
    groups = list(BRACKET.finditer(text))
    if not groups:
        return None
    wg = next((g for g in groups if "周" in g.group(1)), groups[0])
    tail = text[wg.end():].strip()
    room = tail.rsplit("[", 1)[1].split("]", 1)[0].strip() if "[" in tail else None
    head = text[: wg.start()].strip()
    cm = CODE_TAIL.search(head)
    course_code = f"{cm.group(1)}_{cm.group(2)}" if cm else None
    name_class = head[: cm.start()].strip() if cm else head
    course_name, class_name = name_class, None
    if not cm:
        m = CAMPUS_MARK.search(name_class)
        if m:
            course_name = name_class[: m.start()].strip()
            class_name = name_class[m.start():].strip() or None
    if not course_name:
        return None
    return {
        "courseName": course_name,
        "courseCode": course_code,
        "className": class_name,
        "weekText": wg.group(1),
        "teacher": (tail.rsplit("[", 1)[0].strip() or None) if room else (tail or None),
        "room": room or None,
        "selective": selective,
    }


def parse_weeks(raw):
    if not raw:
        return None
    s = re.sub(r"\s+", "", raw)
    out = []
    for m in WEEK_RANGE.finditer(s):
        a = int(m.group(1))
        out.append((a, int(m.group(2))) if m.group(2) else (a, a))
    return out or None


def rows_to_lessons(rows):
    out = []
    for row in rows:
        try:
            period = int(row.get("jcid") or -1)
        except (TypeError, ValueError):
            continue
        if period <= 0 or period >= 12:  # jcid=99 是「无节次」占位行
            continue
        for idx, key in enumerate(("z1", "z2", "z3", "z4", "z5", "z6", "z7")):
            e = parse_cell(row.get(key))
            if not e:
                continue
            out.append({
                "period": period,
                "periodName": row.get("mc"),
                "weekday": idx + 1,
                **e,
                "weeks": parse_weeks(e["weekText"]),
            })
    return out


# ── 脱敏 ─────────────────────────────────────────────────────────────

MASK_WALL = {}


def collect_identifiers(obj):
    """从响应对象里收集“将出现在最终落盘的敏感值”，落盘前统一替换。"""
    if isinstance(obj, dict):
        for k, v in obj.items():
            if k == "teacher" and isinstance(v, str) and v:
                MASK_WALL.setdefault(v, "{教师%d}" % (len(MASK_WALL) + 1))
            elif isinstance(v, (dict, list)):
                collect_identifiers(v)
    elif isinstance(obj, list):
        for it in obj:
            collect_identifiers(it)


def mask_json_text(t):
    for old, new in sorted(MASK_WALL.items(), key=lambda kv: -len(kv[0])):
        t = t.replace(old, new)
    return t


# ── 主流程 ───────────────────────────────────────────────────────────

PASS, FAIL = "[PASS]", "[FAIL]"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--term", help="学期代码；默认用 kbcx_drop 中 selected=true 的")
    ap.add_argument("--out", default=None, help="把原始 JSON 落盘到该目录")
    ap.add_argument("--raw", action="store_true", help="落盘不脱敏（默认脱敏）")
    args = ap.parse_args()

    user = os.environ.get("ZZU_GMS_USER") or ""
    pwd = os.environ.get("ZZU_GMS_PASS") or ""
    if not user:
        user = input("研究生学号: ").strip()
    if not pwd:
        pwd = getpass.getpass("统一身份认证密码（不回显）: ").strip()

    results = []

    def record(name, ok, detail=""):
        results.append(ok)
        print(f"{PASS if ok else FAIL} {name}" + (f"：{detail}" if detail else ""))

    print("\n== 1. 统一认证登录 ==")
    landing, _h = hop(STU_HOME + "index")  # 未登录时手动一步步走链
    login_url = landing["url"] if landing["url"].startswith(CAS_BASE) else f"{CAS_BASE}/cas/a/login?service={STU_SERVICE}"
    page_hop, _h2 = hop(login_url)
    page = page_hop.get("body", "")
    m = EXECUTION_RES.search(page)
    execution = (m.group(1) or m.group(2)) if m else None
    record("打开 CAS 登录页", page_hop.get("status") == 200 and "execution" in page, "")
    record("抽取 execution", bool(execution), "")

    pub_branch, pub_hop = hop(CAS_JWT_PUBKEY)
    pem = pub_hop.get("body", "")
    record("取 CAS 公钥", pub_hop.get("status") == 200 and "BEGIN" in pem, "")
    n, e = pem_to_n_e(pem)
    enc_plain = base64.b64encode(rsa_pkcs1_encrypt(pwd, n, e)).decode("ascii")
    enc_prefixed = "__RSA__" + enc_plain

    logged = False
    for i, enc in enumerate((enc_plain, enc_prefixed), 1):
        form = urllib.parse.urlencode({
            "username": user,
            "password": enc,
            "execution": execution,
            "_eventId": "submit",
            "rememberMe": "true",
            "geolocation": "",
            "fpVisitorId": "",
            "qrCodeKey": "",
            "mfaState": "",
        }).encode()
        final, chain = hop(login_url, data=form)
        if final["url"].startswith(GMS_BASE) and "/cas" not in final["url"]:
            logged = True
            record(f"登录成功（密码形态 {i}）", True, "已回到研究生系统")
            break
        detail = "HTTP %s" % final["status"]
        if "验证码" in final.get("body", ""):
            detail += "，页面提示验证码"
        record(f"登录尝试（密码形态 {i}）", False, detail)
    if not logged:
        print("登录未成功：可能触发验证码/再次 MFA。请按 docs/gms-schedule-api.md 的说明在浏览器核对一次。")
        raise SystemExit(2)

    print("\n== 2. 会话与学期 ==")
    home_hop, _h3 = hop(STU_HOME + "index")
    guid_m = GUID_RE.search(home_hop.get("body", ""))
    guid = guid_m.group(1) if guid_m else None
    record("会话 guid 提取", bool(guid), (guid[:8] + "…") if guid else "")

    def gms_get(action):
        url = f"{GMS_BASE}/stu/{encode_route(action, guid)}?_={int(time.time() * 1000)}"
        r, chain = hop(url)
        return r, chain

    def gms_post(action, fields):
        url = f"{GMS_BASE}/stu/{encode_route(action, guid)}"
        data = urllib.parse.urlencode(fields).encode()
        headers = {
            "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
            "X-Requested-With": "XMLHttpRequest",
            "Accept": "application/json, text/javascript, */*; q=0.01",
            "Referer": STU_HOME,
        }
        r, chain = hop(url, data=data, headers=headers)
        return r, chain

    status, body = gms_get("pygl/xskbcx/kbcx_drop")
    terms = json.loads(body).get("termcode", []) if body.startswith("{") else []
    record("学期接口 kbcx_drop", status == 200 and bool(terms), f"共 {len(terms)} 项")
    termcode = args.term or next((t["value"] for t in terms if t.get("selected")), None)
    print("  当前学期: %s / %s" % (termcode, next((t["text"] for t in terms if t["value"] == termcode), "?")))

    print("\n== 3. 整学期课表 loadKb ==")
    status, body = gms_post("pygl/xskbcx/loadKb", {"kblx": "xs", "termcode": termcode})
    table = json.loads(body) if body.startswith("{") else None
    rows = (table or {}).get("rows", []) or []
    record("课表接口 loadKb", status == 200 and bool(rows), f"rows={len(rows)}")
    lessons = rows_to_lessons(rows)
    names = sorted({l["courseName"] for l in lessons})
    print("  解析 %d 条 / %d 门课：%s" % (len(lessons), len(names), "、".join(names)))

    print("\n== 4. 本周课表 bzkb_load ==")
    status, body = gms_post("pygl/xskbcx/bzkb_load", {})
    week = json.loads(body) if body.startswith("{") else None
    wk = sum(len(d.get("bzkb", [])) for d in (week or {}).get("bzkb", []))
    record("本周视图 bzkb_load", status == 200 and week is not None, f"当周课程 {wk} 条")

    if args.out:
        os.makedirs(args.out, exist_ok=True)
        payload = {"terms": terms, "termTable": table, "weekTable": week, "lessons": lessons}
        text = json.dumps(payload, ensure_ascii=False, indent=2)
        if not args.raw:
            collect_identifiers(payload)
            text = mask_json_text(text)
        for name in ("terms", "termTable", "weekTable", "lessons"):
            with open(os.path.join(args.out, f"gms_{name}.json"), "w", encoding="utf-8") as f:
                f.write(text)
        print("\n已写入 %s（%s）" % (os.path.abspath(args.out), "原文" if args.raw else "已脱敏"))

    passed = sum(1 for ok in results if ok)
    print(f"\n== 结果：{passed}/{len(results)} 通过 ==")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
