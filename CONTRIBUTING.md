# 贡献指南

感谢您对NetProxyGateway项目的兴趣！

## 📋 如何贡献

### 1. 报告问题

如果您发现了bug或有功能建议，请：

1. 检查是否已存在类似问题
2. 提供详细的复现步骤
3. 附上相关日志和截图
4. 描述预期行为和实际行为

### 2. 提交PR

1. Fork项目
2. 创建功能分支：`git checkout -b feature/amazing-feature`
3. 提交更改：`git commit -m 'Add amazing feature'`
4. 推送分支：`git push origin feature/amazing-feature`
5. 创建PR

### 3. 代码规范

#### Kotlin编码规范
- 遵循[Kotlin编码规范](https://kotlinlang.org/docs/coding-conventions.html)
- 使用有意义的变量名和函数名
- 添加必要的注释和文档
- 编写单元测试

#### 项目结构规范
- 每个模块独立开发
- 模块间通过接口通信
- 遵循依赖注入原则
- 保持代码整洁和可读

### 4. 开发环境设置

#### 前置要求
- Android Studio Hedgehog | 2023.1.1+
- JDK 17
- Android 8.0+ (API 26) 设备

#### 设置步骤
1. 克隆仓库：`git clone https://github.com/your-username/NetProxyGateway.git`
2. 打开Android项目
3. 同步Gradle依赖
4. 运行应用

### 5. 模块开发指南

#### 模块划分
项目采用模块化架构，分为以下模块：

1. **Core Module** - 核心模块
2. **Communication Module** - 通信模块
3. **Network Module** - 网络模块
4. **WiFi Module** - WiFi模块
5. **UI Module** - UI模块
6. **Config Module** - 配置模块
7. **Test Module** - 测试模块

#### 模块开发流程
1. 阅读模块接口定义
2. 实现模块功能
3. 编写单元测试
4. 提交PR并等待审查

### 6. 提交规范

#### Commit消息格式
```
<type>(<scope>): <subject>

<body>

<footer>
```

#### Type类型
- `feat`: 新功能
- `fix`: 修复bug
- `docs`: 文档更新
- `style`: 代码格式调整
- `refactor`: 重构
- `test`: 测试相关
- `chore`: 构建过程或辅助工具的变动

#### 示例
```
feat(network): 实现SOCKS5代理服务

- 使用Netty实现SOCKS5协议
- 支持TCP连接转发
- 添加连接池管理

Closes #123
```

### 7. 代码审查

#### 审查清单
- [ ] 代码符合项目规范
- [ ] 功能完整且正确
- [ ] 单元测试覆盖率足够
- [ ] 文档已更新
- [ ] 没有引入新的依赖

#### 审查流程
1. 提交PR后，等待审查
2. 根据反馈修改代码
3. 通过审查后合并

### 8. 社区行为准则

我们采用[Contributor Covenant](https://www.contributor-covenant.org/)行为准则：

- 使用友好和包容的语言
- 尊重不同的观点和经历
- 专注于建设性的反馈
- 对社区成员保持同理心

## 📚 资源

- [技术规范](SPEC.md)
- [实现计划](docs/plans/2026-03-09-NetProxyGateway-implementation-plan.md)
- [Android项目说明](android/README.md)

## 📝 许可证

本项目采用MIT许可证，详见 [LICENSE](LICENSE)。