#!/bin/bash
# .git/hooks/pre-commit
# 轻量级检查 — 不运行 Gradle，只做静态分析

echo "=== Pre-commit Checks ==="
echo ""

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

ERRORS=0

# 1. 检查是否有 AndroidManifest.xml 中的硬编码密钥
echo -n "Checking for hardcoded secrets... "
if grep -r "api_key\|password\|secret\|token" --include="*.xml" --include="*.java" --include="*.kt" . 2>/dev/null | grep -v "test\|example\|sample" | grep -v "\.gradle" > /dev/null; then
    echo -e "${YELLOW}Warning: Possible hardcoded secrets found${NC}"
    grep -r "api_key\|password\|secret\|token" --include="*.xml" --include="*.java" --include="*.kt" . 2>/dev/null | grep -v "test\|example\|sample" | grep -v "\.gradle" | head -5
    ((ERRORS++))
else
    echo -e "${GREEN}OK${NC}"
fi

# 2. 检查 Java/Kotlin 文件语法（基础检查）
echo -n "Checking Java/Kotlin syntax... "
SYNTAX_ERRORS=0
for file in $(git diff --cached --name-only --diff-filter=ACM | grep -E "\.(java|kt)$" | head -20); do
    if [ -f "$file" ]; then
        # 基础括号匹配检查
        OPEN=$(grep -o '{' "$file" | wc -l)
        CLOSE=$(grep -o '}' "$file" | wc -l)
        if [ "$OPEN" -ne "$CLOSE" ]; then
            echo -e "${RED}ERROR: Bracket mismatch in $file (open: $OPEN, close: $CLOSE)${NC}"
            ((SYNTAX_ERRORS++))
        fi
    fi
done
if [ $SYNTAX_ERRORS -eq 0 ]; then
    echo -e "${GREEN}OK${NC}"
else
    ((ERRORS++))
fi

# 3. 检查 XML 资源文件
echo -n "Checking XML resources... "
XML_ERRORS=0
for file in $(git diff --cached --name-only --diff-filter=ACM | grep -E "\.xml$" | head -20); do
    if [ -f "$file" ]; then
        # XML 格式检查（简单验证）
        if ! python3 -c "import xml.etree.ElementTree as ET; ET.parse('$file')" 2>/dev/null; then
            echo -e "${RED}ERROR: Invalid XML in $file${NC}"
            ((XML_ERRORS++))
        fi
    fi
done
if [ $XML_ERRORS -eq 0 ]; then
    echo -e "${GREEN}OK${NC}"
else
    ((ERRORS++))
fi

# 4. 检查文件大小（防止意外提交大文件）
echo -n "Checking file sizes... "
LARGE_FILES=$(git diff --cached --name-only --diff-filter=ACM | while read file; do
    if [ -f "$file" ]; then
        size=$(stat -f%z "$file" 2>/dev/null || stat -c%s "$file" 2>/dev/null)
        if [ "$size" -gt 10485760 ] 2>/dev/null; then  # 10MB
            echo "$file"
        fi
    fi
done)
if [ -n "$LARGE_FILES" ]; then
    echo -e "${YELLOW}Warning: Large files detected:${NC}"
    echo "$LARGE_FILES"
    ((ERRORS++))
else
    echo -e "${GREEN}OK${NC}"
fi

# 5. 检查是否有 TODO/FIXME 标记
echo -n "Checking for TODOs... "
TODOS=$(git diff --cached --name-only --diff-filter=ACM | xargs grep -l "TODO\|FIXME\|HACK" 2>/dev/null | head -5)
if [ -n "$TODOS" ]; then
    echo -e "${YELLOW}Found TODOs in:${NC}"
    echo "$TODOS"
    # TODO 不阻止提交，只是提醒
else
    echo -e "${GREEN}OK${NC}"
fi

echo ""
echo "=== Summary ==="
if [ $ERRORS -gt 0 ]; then
    echo -e "${RED}$ERRORS error(s) found. Commit aborted.${NC}"
    echo "Fix the errors above and try again."
    exit 1
else
    echo -e "${GREEN}All checks passed!${NC}"
    echo ""
    echo "Note: Full build will run on GitHub Actions after push."
    echo "Use: .\\gradle-ci.ps1 -Watch to monitor the build."
    exit 0
fi
