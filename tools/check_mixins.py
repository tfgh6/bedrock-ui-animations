#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""静态核对 Mixin 注入目标是否真的存在。

为什么需要这个工具
------------------
`ui-transitions.mixins.json` 里写的是 `"injectors": {"defaultRequire": 0}`，
这是为了跨版本容错：某个注入点在新版里没了，Mixin 只会**静默跳过**，不会报错。
代价是描述符写错、目标方法改名、调用点被删，全都表现为"功能莫名失效"且没有任何日志。

本工具在构建期把这些情况变成硬错误。它检查三件事：

  1. `@Mixin(X.class)` 的目标类在客户端 jar 里存在；
  2. `@Inject/@ModifyVariable/@Redirect/...` 的 `method = ...` 所指方法存在
     （含父类链；写了完整描述符时要求精确匹配）；
  3. `@At(target = "...")` 指向的成员存在，**并且**被注入的那个方法所在类的常量池里
     确实引用了它 —— 也就是那条指令还在，注入才可能命中。

只依赖标准库：自己解析 class 文件常量池，不需要 javap / ASM。

用法：
    python tools/check_mixins.py                       # 自动找 build/mc/client-26.3.jar
    python tools/check_mixins.py --client-jar <path>
    python tools/check_mixins.py --quiet               # 只输出一行结论
退出码：0 = 全部命中；1 = 有目标找不到。
"""

import argparse
import json
import os
import re
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
MIXIN_SRC = os.path.join(REPO, "ui-transitions", "src")
MIXIN_CFG = os.path.join(REPO, "ui-transitions", "resources", "ui-transitions.mixins.json")
DEFAULT_JAR = os.path.join(REPO, "build", "mc", "client-26.3.jar")

# ---------------------------------------------------------------- class 文件解析

CP_SIZES = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4,
            12: 4, 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}
CP_WIDE = {5, 6}          # Long / Double 占两个常量池槽

# 操作码的定长部分（不含操作码自身）；变长的 tableswitch/lookupswitch/wide 单独处理
_OP_LEN = {}
for _op in list(range(0x00, 0x10)) + list(range(0x1A, 0x36)) + \
        list(range(0x3B, 0x84)) + list(range(0x85, 0x99)) + \
        list(range(0xAC, 0xB2)) + [0xBE, 0xBF, 0xC2, 0xC3, 0xCA, 0xFE, 0xFF]:
    _OP_LEN[_op] = 1
for _op in [0x10, 0x12, 0x15, 0x16, 0x17, 0x18, 0x19,
            0x36, 0x37, 0x38, 0x39, 0x3A, 0xA9, 0xBC]:
    _OP_LEN[_op] = 2
for _op in [0x11, 0x13, 0x14, 0x84, 0x99, 0x9A, 0x9B, 0x9C, 0x9D, 0x9E, 0x9F,
            0xA0, 0xA1, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6, 0xA7, 0xA8,
            0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8, 0xBB, 0xBD, 0xC0, 0xC1,
            0xC6, 0xC7]:
    _OP_LEN[_op] = 3
for _op in [0xB9, 0xBA, 0xC8, 0xC9]:
    _OP_LEN[_op] = 5
_OP_LEN[0xC5] = 4                                  # multianewarray


def instruction_length(code, pc):
    """返回 pc 处指令的总字节数（含操作码）"""
    op = code[pc]
    if op == 0xAA:                                 # tableswitch：4 字节对齐
        pad = (4 - ((pc + 1) % 4)) % 4
        base = pc + 1 + pad
        low = struct.unpack_from(">i", code, base + 4)[0]
        high = struct.unpack_from(">i", code, base + 8)[0]
        return 1 + pad + 12 + 4 * (high - low + 1)
    if op == 0xAB:                                 # lookupswitch
        pad = (4 - ((pc + 1) % 4)) % 4
        base = pc + 1 + pad
        npairs = struct.unpack_from(">i", code, base + 4)[0]
        return 1 + pad + 8 + 8 * npairs
    if op == 0xC4:                                 # wide
        return 6 if code[pc + 1] == 0x84 else 4    # wide iinc 多一个 2 字节立即数
    return _OP_LEN.get(op, 1)


class ClassFile:
    """只解析我们需要的部分：常量池、父类、方法表。"""

    def __init__(self, data, name):
        self.name = name
        magic, _minor, self.major = struct.unpack_from(">IHH", data, 0)
        if magic != 0xCAFEBABE:
            raise ValueError("不是 class 文件: %s" % name)
        cp_count = struct.unpack_from(">H", data, 8)[0]
        self.utf8 = {}
        self.class_name_index = {}
        self.nameandtype = {}
        self.member_refs = set()          # (owner, name, descriptor) —— Field/Method/InterfaceMethod
        self.cp_refs = {}                 # 常量池下标 -> (owner, name, descriptor)
        o = 10
        i = 1
        while i < cp_count:
            tag = data[o]
            o += 1
            if tag == 1:
                ln = struct.unpack_from(">H", data, o)[0]
                self.utf8[i] = data[o + 2:o + 2 + ln].decode("utf-8", "replace")
                o += 2 + ln
            elif tag == 7:
                self.class_name_index[i] = struct.unpack_from(">H", data, o)[0]
                o += 2
            elif tag == 12:
                self.nameandtype[i] = struct.unpack_from(">HH", data, o)
                o += 4
            elif tag in (9, 10, 11):
                ci, nti = struct.unpack_from(">HH", data, o)
                self._refs_pending = getattr(self, "_refs_pending", [])
                self._refs_pending.append((i, ci, nti))
                o += 4
            else:
                o += CP_SIZES[tag]
            i += 2 if tag in CP_WIDE else 1

        # 常量池解析完才能把 Class 索引和 NameAndType 解析出来
        for index, ci, nti in getattr(self, "_refs_pending", []):
            owner = self.class_of(ci)
            nt = self.nameandtype.get(nti)
            if owner is None or nt is None:
                continue
            n_name = self.utf8.get(nt[0])
            n_desc = self.utf8.get(nt[1])
            if n_name is not None and n_desc is not None:
                self.member_refs.add((owner, n_name, n_desc))
                self.cp_refs[index] = (owner, n_name, n_desc)

        o += 2                                  # access_flags
        this_class = struct.unpack_from(">H", data, o)[0]
        self.this_name = self.class_of(this_class)
        o += 2
        super_index = struct.unpack_from(">H", data, o)[0]
        self.super_name = self.class_of(super_index)
        o += 2
        iface_count = struct.unpack_from(">H", data, o)[0]
        o += 2
        self.interfaces = []
        for _ in range(iface_count):
            self.interfaces.append(self.class_of(struct.unpack_from(">H", data, o)[0]))
            o += 2

        o = self._skip_members(data, o)         # fields
        self.methods = {}
        self.method_code_refs = {}              # (name, desc) -> set((owner,name,desc)) 调用指令
        method_count = struct.unpack_from(">H", data, o)[0]
        o += 2
        for _ in range(method_count):
            _flags, name_i, desc_i = struct.unpack_from(">HHH", data, o)
            o += 6
            attrs, o = self._read_attrs(data, o)
            m_name = self.utf8.get(name_i)
            m_desc = self.utf8.get(desc_i)
            if m_name is None or m_desc is None:
                continue
            self.methods.setdefault(m_name, set()).add(m_desc)
            code = attrs.get("Code")
            if code is not None:
                self.method_code_refs[(m_name, m_desc)] = self._scan_code_refs(code)

    def class_of(self, index):
        if index == 0:
            return None
        return self.utf8.get(self.class_name_index.get(index, -1))

    def _read_attrs(self, data, o):
        """返回 ({属性名: 属性体 bytes}, 结束位置)。只保留 Code，省内存。"""
        count = struct.unpack_from(">H", data, o)[0]
        o += 2
        attrs = {}
        for _ in range(count):
            name_i = struct.unpack_from(">H", data, o)[0]
            length = struct.unpack_from(">I", data, o + 2)[0]
            if self.utf8.get(name_i) == "Code":
                attrs["Code"] = data[o + 6:o + 6 + length]
            o += 6 + length
        return attrs, o

    @staticmethod
    def _skip_attrs(data, o):
        count = struct.unpack_from(">H", data, o)[0]
        o += 2
        for _ in range(count):
            o += 2
            length = struct.unpack_from(">I", data, o)[0]
            o += 4 + length
        return o

    def _scan_code_refs(self, code):
        """扫 Code 属性里的调用指令，收集被引用的 (owner, name, desc)。

        只关心 invoke*：它们是 @At(INVOKE) 是否成立的关键。
        """
        refs = set()
        length = struct.unpack_from(">I", code, 4)[0]
        pc, end = 8, 8 + length
        while pc < end:
            op = code[pc]
            if op in (0xB6, 0xB7, 0xB8, 0xB9):        # invokevirtual/special/static/interface
                idx = struct.unpack_from(">H", code, pc + 1)[0]
                ref = self.cp_refs.get(idx)
                if ref:
                    refs.add(ref)
            pc += instruction_length(code, pc)
        return refs

    @classmethod
    def _skip_members(cls, data, o):
        count = struct.unpack_from(">H", data, o)[0]
        o += 2
        for _ in range(count):
            o += 6
            o = cls._skip_attrs(data, o)
        return o

    def has_method(self, name, desc):
        descs = self.methods.get(name)
        if descs is None:
            return False
        return desc in descs or desc is None


class JarIndex:
    def __init__(self, path):
        self.path = path
        self._cache = {}
        import zipfile
        self._zip = zipfile.ZipFile(path)
        self._names = {n[:-len(".class")] for n in self._zip.namelist() if n.endswith(".class")}

    def get(self, internal_name):
        """内部名 -> ClassFile（不存在返回 None）"""
        if internal_name in self._cache:
            return self._cache[internal_name]
        result = None
        if internal_name in self._names:
            try:
                result = ClassFile(self._zip.read(internal_name + ".class"), internal_name)
            except Exception:                                  # noqa: BLE001
                result = None
        self._cache[internal_name] = result
        return result

    def hierarchy(self, internal_name):
        """自己 + 父类 + 接口，广度优先（只为查方法是否存在）"""
        seen, queue = [], [internal_name]
        while queue:
            current = queue.pop(0)
            if current is None or current in seen:
                continue
            cls = self.get(current)
            if cls is None:
                seen.append(current)          # 记下来避免重复，但拿不到成员
                continue
            seen.append(current)
            queue.append(cls.super_name)
            queue.extend(cls.interfaces)
        return seen

    def has_method(self, internal_name, name, desc):
        for cls_name in self.hierarchy(internal_name):
            cls = self.get(cls_name)
            if cls is None:
                continue
            if desc is None:
                if name in cls.methods:
                    return True
            elif cls.has_method(name, desc):
                return True
        return False

    def has_member_ref(self, holder, owner, name, desc):
        """holder 的常量池里是否引用了 owner.name:desc（类级别，弱判断）"""
        cls = self.get(holder)
        if cls is None:
            return None                        # 无法判断
        return (owner, name, desc) in cls.member_refs

    def method_invokes(self, holder, m_name, m_desc, owner, name, desc):
        """方法级判断：holder.m_name(m_desc) 的字节码里是否真的有一条对 owner.name:desc 的调用。

        这比常量池级别严格得多 —— 常量池里有引用不代表那条指令在被注入的方法体内。
        返回 True / False / None（无法判断）。
        """
        cls = self.get(holder)
        if cls is None:
            return None
        # 目标方法可能声明在父类：沿父类链找真正声明它的那个类
        for cls_name in self.hierarchy(holder):
            c = self.get(cls_name)
            if c is None:
                continue
            if m_desc is not None and not c.has_method(m_name, m_desc):
                continue
            if m_desc is None and m_name not in c.methods:
                continue
            refs = c.method_code_refs.get((m_name, m_desc)) if m_desc else None
            if refs is None and m_desc is None:
                merged = set()
                for d in c.methods.get(m_name, ()):  
                    merged |= c.method_code_refs.get((m_name, d), set())
                refs = merged
            if refs is None:
                return None                    # 抽象/无 Code（native、abstract）
            return (owner, name, desc) in refs
        return None


# ---------------------------------------------------------------- Java 源码解析

IMPORT_RE = re.compile(r"^\s*import\s+(?:static\s+)?([\w.$]+)\s*;", re.M)
MIXIN_RE = re.compile(r"@Mixin\s*\(\s*(?:value\s*=\s*)?\{?\s*([\w.$]+)\s*\.class")
STR_CONST_RE = re.compile(
    r"(?:private|public|protected)?\s*static\s+final\s+String\s+(\w+)\s*=\s*((?:\s*\"(?:[^\"\\]|\\.)*\"\s*\+?)+)\s*;")
LITERAL_RE = re.compile(r"\"((?:[^\"\\]|\\.)*)\"")
ANNOTATION_RE = re.compile(
    r"@(Inject|ModifyVariable|ModifyArg|ModifyArgs|ModifyConstant|Redirect|WrapOperation|ModifyExpressionValue)\b")


def read_text(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def skip_string(text, i):
    """i 指向开引号，返回闭引号之后的位置（处理转义）"""
    i += 1
    while i < len(text):
        if text[i] == "\\":
            i += 2
            continue
        if text[i] == '"':
            return i + 1
        i += 1
    return i


def balanced_block(text, start):
    """start 指向 '('，返回匹配的 ')' 之后的位置（忽略字符串字面量里的括号）"""
    depth, i = 0, start
    while i < len(text):
        ch = text[i]
        if ch == '"':
            i = skip_string(text, i)
            continue
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    return len(text)


def extract_value(text, pos):
    """从 pos 开始读一个注解元素的值：字符串字面量（可跨行 + 拼接）或标识符。

    返回 (原始表达式, 结束位置)。到 ',' 或 ')' 或换行后的下一个元素为止。
    """
    start = pos
    while pos < len(text) and text[pos] in " \t\r\n":
        pos += 1
    if pos < len(text) and text[pos] == '"':
        while pos < len(text):
            pos = skip_string(text, pos)
            j = pos
            while j < len(text) and text[j] in " \t\r\n":
                j += 1
            if j < len(text) and text[j] == "+":
                pos = j + 1
                while pos < len(text) and text[pos] in " \t\r\n":
                    pos += 1
                continue
            break
    else:
        while pos < len(text) and (text[pos].isalnum() or text[pos] in "_$."):
            pos += 1
    return text[start:pos].strip(), pos


def find_attr(block, name):
    """在注解参数块里找 `name = <value>`，返回原始表达式（找不到返回 None）"""
    for m in re.finditer(r"(?<![\w$])%s\s*=" % re.escape(name), block):
        return extract_value(block, m.end())[0]
    return None


def resolve_class(expr, imports):
    """把 `Screen.class` / `net.minecraft...Screen.class` 解析成内部名"""
    expr = expr.strip()
    if expr.endswith(".class"):
        expr = expr[:-len(".class")]
    expr = expr.strip()
    if not re.fullmatch(r"[\w.$]+", expr):
        return None
    if "." in expr and expr[0].islower():
        return expr.replace(".", "/")
    for fq in imports:
        if fq.rsplit(".", 1)[-1] == expr:
            return fq.replace(".", "/")
    return None


def eval_string_expr(expr, constants):
    """把 `EXTRACT_ALL` 或 `"a" + "b"` 求值成字符串；求不出来返回 None"""
    if expr is None:
        return None
    expr = expr.strip()
    if expr in constants:
        return constants[expr]
    if expr.startswith('"'):
        parts = LITERAL_RE.findall(expr)
        if not parts or expr.count('"') % 2 != 0:
            return None
        return "".join(p.encode().decode("unicode_escape") for p in parts)
    return None


def split_method_spec(spec):
    """`<init>(...)V` -> ('<init>', '(...)V')；`foo` -> ('foo', None)"""
    idx = spec.find("(")
    if idx < 0:
        return spec, None
    return spec[:idx], spec[idx:]


def parse_mixin_file(path):
    text = read_text(path)
    imports = IMPORT_RE.findall(text)

    m = MIXIN_RE.search(text)
    target_expr = m.group(1) if m else None
    target = resolve_class(target_expr, imports) if target_expr else None

    constants = {}
    for name, raw in STR_CONST_RE.findall(text):
        parts = LITERAL_RE.findall(raw)
        if parts:
            constants[name] = "".join(p.encode().decode("unicode_escape") for p in parts)

    sites = []
    for m in ANNOTATION_RE.finditer(text):
        paren = text.find("(", m.end())
        if paren < 0:
            continue
        end = balanced_block(text, paren)
        block = text[paren:end]
        sites.append(dict(
            kind=m.group(1),
            method=eval_string_expr(find_attr(block, "method"), constants),
            raw_method=find_attr(block, "method"),
            at_target=eval_string_expr(find_attr(block, "target"), constants),
            line=text.count("\n", 0, m.start()) + 1,
        ))
    return target, sites


TARGET_REF_RE = re.compile(r"^L([\w/$]+);([\w$<>]+)(\([^)]*\))?(.+)?$")


def parse_target_ref(spec):
    """`Lowner;name(desc)ret` -> (owner, name, 'desc ret')；字段引用 -> (owner, name, 'I')"""
    m = TARGET_REF_RE.match(spec.strip())
    if not m:
        return None
    owner, name, args, ret = m.group(1), m.group(2), m.group(3), m.group(4)
    if args:                                   # 方法：描述符要带上返回类型
        return owner, name, args + (ret or "")
    return owner, name, ret or None            # 字段：剩下的就是类型描述符


# ---------------------------------------------------------------- 主流程

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--client-jar", default=DEFAULT_JAR)
    parser.add_argument("--src", default=MIXIN_SRC, help="mixin 源码根（默认 ui-transitions/src）")
    parser.add_argument("--config", default=MIXIN_CFG, help="mixin 配置 json")
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args()

    if not os.path.isfile(args.client_jar):
        sys.exit("找不到客户端 jar: %s" % args.client_jar)
    if not os.path.isfile(args.config):
        sys.exit("找不到 mixin 配置: %s" % args.config)

    with open(args.config, encoding="utf-8") as fh:
        cfg = json.load(fh)
    package = cfg["package"]
    classes = cfg.get("client", [])
    pkg_dir = os.path.join(args.src, *package.split("."))

    jar = JarIndex(args.client_jar)
    problems = []
    checked_sites = 0
    checked_targets = 0

    for name in classes:
        path = os.path.join(pkg_dir, name + ".java")
        if not os.path.isfile(path):
            problems.append("%s.java: 源文件不存在" % name)
            continue
        target, sites = parse_mixin_file(path)
        if target is None:
            problems.append("%s: 无法解析 @Mixin 目标类" % name)
            continue
        checked_targets += 1
        if jar.get(target) is None:
            problems.append("%s: 目标类 %s 不在客户端 jar 里" % (name, target.replace("/", ".")))
            continue

        for site in sites:
            checked_sites += 1
            where = "%s:%d @%s" % (name, site["line"], site["kind"])

            spec = site["method"]
            if spec is None:
                problems.append("%s: 注解里没有可解析的 method（是否用了常量拼接？）" % where)
                continue
            m_name, m_desc = split_method_spec(spec)
            if not jar.has_method(target, m_name, m_desc):
                problems.append("%s: 目标方法不存在 %s.%s%s"
                                % (where, target.replace("/", "."), m_name, m_desc or ""))

            ref = parse_target_ref(site["at_target"]) if site["at_target"] else None
            if site["at_target"] and ref is None:
                problems.append("%s: @At target 无法解析: %r" % (where, site["at_target"]))
            elif ref is not None:
                owner, r_name, r_desc = ref
                if r_desc is not None and not jar.has_method(owner, r_name, r_desc):
                    problems.append("%s: @At 目标成员不存在 %s.%s%s"
                                    % (where, owner.replace("/", "."), r_name, r_desc))
                elif r_desc is None and not jar.has_method(owner, r_name, None):
                    problems.append("%s: @At 目标成员不存在 %s.%s"
                                    % (where, owner.replace("/", "."), r_name))
                # 被注入的方法体里必须真的有这条调用指令，否则 @At 锚点不存在
                hit = jar.method_invokes(target, m_name, m_desc, owner, r_name, r_desc)
                if hit is False:
                    problems.append(
                        "%s: 注入点不成立 —— %s.%s%s 的字节码里没有对 %s.%s%s 的调用"
                        % (where, target.replace("/", "."), m_name, m_desc or "",
                           owner.replace("/", "."), r_name, r_desc))

    problems = sorted(set(problems))
    if problems:
        print("Mixin 注入目标核对: %d 个问题" % len(problems))
        for p in problems:
            print("  [未命中] " + p)
        return 1

    if args.quiet:
        print("Mixin 目标核对: %d 个 mixin / %d 个注入点全部命中" % (checked_targets, checked_sites))
    else:
        print("Mixin 注入目标核对: 全部命中")
        print("  目标类 %d 个，注入点 %d 个，客户端 %s"
              % (checked_targets, checked_sites, os.path.basename(args.client_jar)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
