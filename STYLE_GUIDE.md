# NetProxyGateway 代码风格规范

## 1. 通用规范

### 1.1 换行符
- **统一使用 LF (Unix 风格)**
- 通过 `.gitattributes` 强制：`* text=auto eol=lf`

### 1.2 编码
- UTF-8 无 BOM

### 1.3 尾随空格
- 禁止尾随空格
- 文件末尾保留一个空行

## 2. Kotlin (Android) 规范

### 2.1 缩进
- 4 个空格，禁止使用 Tab

### 2.2 命名规范
- 类名：PascalCase
- 函数/变量：camelCase
- 常量（const val）：SCREAMING_SNAKE_CASE
- 私有常量：SCREAMING_SNAKE_CASE

### 2.3 导入顺序
1. Android/平台导入
2. 第三方库导入
3. Java 标准库导入
4. 同项目导入
5. 别名导入放最后
- 禁止使用通配符导入 (`.*`)
- 按字母顺序排列

### 2.4 注解位置
- 注解与修饰符同行：`@Volatile private var`

### 2.5 注释
- 类/函数文档：使用 `/** */`（KDoc）
- 行内注释：使用 `//`
- **统一使用中文注释**（与项目现有主体一致）

### 2.6 括号风格
- K&R 风格（大括号不换行）

### 2.7 空行
- 类之间：2 个空行
- 函数之间：1 个空行
- 逻辑段之间：1 个空行
- 禁止连续空行（>1）

## 3. Go (Server) 规范

### 3.1 缩进
- Tab（Go 标准）
- 使用 `gofmt` 自动格式化

### 3.2 命名规范
- 导出：PascalCase
- 未导出：camelCase
- 常量：PascalCase（导出）或 camelCase（未导出）
- 错误变量：`ErrXxx`（导出）或 `errXxx`（未导出）
- 测试函数：TestXxx（驼峰，无下划线）

### 3.3 导入顺序
- 标准库一组
- 第三方库一组
- 本地库一组
- 组间空行分隔
- 使用 `goimports` 自动格式化

### 3.4 错误处理
- 哨兵错误：`var ErrXxx = errors.New("...")`
- 动态错误：`fmt.Errorf("failed to xxx: %w", err)`
- 禁止字符串错误常量

### 3.5 注释
- **统一使用英文注释**
- 包注释：`// Package xxx ...`
- 导出函数必须添加文档注释
- 函数注释以函数名开头

### 3.6 函数设计
- 函数长度不超过 50 行
- 多参数（>3）或超 80 字符时换行
- 优先使用表驱动测试

### 3.7 并发
- 统一使用 `defer mu.Unlock()`
- 网络操作接受 `context.Context`

## 4. 工具配置

### 4.1 EditorConfig
```ini
root = true

[*]
charset = utf-8
end_of_line = lf
insert_final_newline = true
trim_trailing_whitespace = true

[*.{kt,kts}]
indent_style = space
indent_size = 4

[*.go]
indent_style = tab

[*.{md,yml,yaml}]
indent_style = space
indent_size = 2
```

### 4.2 推荐工具
- Kotlin: ktlint
- Go: gofmt, goimports, golint
