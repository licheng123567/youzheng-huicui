#!/usr/bin/env python3
"""路由覆盖检查(补 Gate1 盲区)：契约每个 operation 必须有对应后端 @*Mapping handler。
Gate1(schemathesis -m positive + 排除 status_code_conformance)无法检出 missing handler——
缺 handler 返 404,被 not_a_server_error/response_schema_conformance 放过。本检查直接对账,无 fuzz 噪声。
用法: python3 backend/scripts/route_coverage.py   (退出码 1=有契约操作无 handler)
"""
import re, sys, glob, yaml, os

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SPEC = os.path.join(ROOT, "docs/api/openapi-core.yaml")
WEB = os.path.join(ROOT, "backend/app/src/main/java/com/youzheng/huicui/web")

norm = lambda p: re.sub(r"\{[^}]+\}", "{}", p.rstrip("/")) or "/"

# 1) 契约操作 (METHOD, 规范化path)
spec = yaml.safe_load(open(SPEC))
contract = set()
for path, item in spec["paths"].items():
    for verb in ("get", "post", "put", "patch", "delete"):
        if verb in item:
            contract.add((verb.upper(), norm(path)))

# 2) 控制器 handler：类级 @RequestMapping 前缀 + 方法级 @{Verb}Mapping
VERB = {"Get": "GET", "Post": "POST", "Put": "PUT", "Patch": "PATCH", "Delete": "DELETE"}
handlers = set()
for f in glob.glob(os.path.join(WEB, "*.java")):
    src = open(f).read()
    m = re.search(r'@(?:[\w.]*\.)?RequestMapping\(\s*"([^"]*)"', src)
    base = m.group(1) if m else ""
    # 允许全限定注解(@org.springframework.web.bind.annotation.GetMapping)
    for verb, path in re.findall(r'@(?:[\w.]*\.)?(Get|Post|Put|Patch|Delete)Mapping\(\s*"([^"]*)"', src):
        handlers.add((VERB[verb], norm(base + path)))

missing = sorted(contract - handlers)
print(f"契约操作 {len(contract)} · 控制器 handler {len(handlers)} · 缺 handler {len(missing)}")
for verb, p in missing:
    print(f"  ❌ 无 handler: {verb} {p}")
if missing:
    print("::error::存在契约声明但后端无 handler 的端点(Gate1 盲区,missing→404 被放过)")
    sys.exit(1)
print("✅ 契约全部操作均有后端 handler")
