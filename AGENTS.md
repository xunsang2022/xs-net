# AGENTS.md

## 指令读取规范

- 每次对话开始时，必须从当前工作树中重新读取 `AGENTS.md`；禁止仅依赖对话上下文中已有的 `AGENTS.md` 内容。
- 为保证约束一致性，可以先计算当前工作树中 `AGENTS.md` 的 MD5，并与已知基准进行比较，以判断文件是否发生变化；如果发生变化，必须以当前文件内容为准。

## Maven 构建环境（本仓库）

- 本机没有全局 Maven；统一使用仓库根目录的 `.\mvnw.cmd` 包装脚本，它内部调用 IntelliJ IDEA 自带 Maven。

- `.\mvnw.cmd` 会固定 `JAVA_HOME`（JDK 21）并显式传入 `-Dmaven.repo.local="$env:USERPROFILE\.m2\repository"`，避免 Maven 解析到 `C:\.m2\repository` 导致构建失败。

- 若必须直接调用 Maven，路径为 `F:\Program Files\JetBrains\IntelliJ IDEA 2025.1.1\plugins\maven\lib\maven3\bin\mvn.cmd`（3.9.9），并必须显式传 `-Dmaven.repo.local="$env:USERPROFILE\.m2\repository"`；不要依赖 PATH 中是否存在 `mvn`。

# git工作规范

## 工作原则
0. 任何工作树都必须以主工作树中的 `AGENTS.md` 为准；其他工作树中的 `AGENTS.md` 不得覆盖或替代主工作树的规则。
1. 主工作树(非git worktree add创建)且分支为 `master`：可以修改，但不能提交(对话中明确说明除外)。
2. 非主工作树：必须创建单独分支，并在该分支上修改。

## 提交消息格式

- 提交信息必须准确概括实际变更内容；禁止使用空泛、敷衍、与变更无关或仅描述提交动作的表述，例如“合并最近四次修改”。

### 📝 标准格式

一个符合 Conventional Commits 规范的提交信息，结构如下：

```
<类型>[可选的作用域]: <描述>

[可选的正文]

[可选的页脚]
```

- **标题行（必填）**：`类型(作用域): 描述`。类型和描述之间用冒号和空格隔开，作用域可选。
- **正文（可选）**：详细解释**为什么**做这次修改，与标题行之间必须空一行。
- **页脚（可选）**：关联 Issue（如 `Closes #123`）或声明破坏性变更（`BREAKING CHANGE`）。

### 📌 类型（Type）列表

这是规范里最关键的部分，用来说明这次提交的**性质**：

| 类型           | 含义                  |
| :----------- | :------------------ |
| **feat**     | 新增功能                |
| **fix**      | 修复 Bug              |
| **docs**     | 只修改了文档              |
| **style**    | 代码格式调整（不影响逻辑）       |
| **refactor** | 代码重构（既非新功能，也非修 Bug） |
| **perf**     | 性能优化                |
| **test**     | 新增或修改测试             |
| **chore**    | 构建流程、依赖更新等杂项        |
| **ci**       | 持续集成配置修改            |
