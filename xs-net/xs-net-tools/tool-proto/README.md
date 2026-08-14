# tool-proto

Java类自动生成protobuf文件的工具

## 项目简介

tool-proto 是一个独立工具，用于将带有 `@ProtobufClass` 注解的 Java 类自动生成对应的 protobuf (.proto) 文件。

## 特性

- 🚀 **简单易用** - 只需添加注解即可自动生成proto文件
- 📝 **类型映射** - 自动映射Java基本类型到proto类型
- 🔧 **灵活配置** - 支持自定义生成目录和包路径
- 📦 **文件合并** - 支持将多个类合并到同一个proto文件
- 🎯 **零侵入** - 基于注解，不影响原有代码结构

## 快速开始

### 添加依赖

```xml
<dependency>
    <groupId>com.proto.generator</groupId>
    <artifactId>proto-generator</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 创建Java类

```java
@ProtobufClass
public class UserMessage {
    /** 用户ID */
    public long userId;
    
    /** 用户名 */
    public String username;
    
    /** 年龄 */
    public int age;
    
    /** 是否激活 */
    public boolean active;
}
```

### 生成proto文件

```java
// 简单使用
ProtoGenerator.quickGenerate("com.example.proto");

// 自定义配置
new ProtoGenerator()
    .setProtoPackagePath("com.example.proto")
    .setGenerateFolder("./target/proto")
    .generate();
```

## 注解说明

### @ProtobufClass
标记需要生成proto的Java类

### @ProtoFileMerge
将多个类合并到同一个proto文件中

```java
@ProtobufClass
@ProtoFileMerge(fileName = "user", filePackage = "pb.user")
public class LoginMessage {
    public String username;
    public String password;
}
```

## 类型映射

| Java类型 | Proto类型 |
|----------|-----------|
| int/Integer | int32 |
| long/Long | int64 |
| float/Float | float |
| double/Double | double |
| boolean/Boolean | bool |
| String | string |
| byte[] | bytes |

## 目录结构

```
proto-generator/
├── src/
│   ├── main/
│   │   └── java/
│   │       └── com/proto/generator/
│   │           ├── annotation/          # 注解定义
│   │           ├── core/               # 核心生成逻辑
│   │           ├── util/               # 工具类
│   │           └── ProtoGenerator.java  # 主入口类
│   └── test/
│       └── java/
│           └── com/proto/generator/
│               ├── example/            # 示例类
│               └── ProtoGeneratorTest.java
├── pom.xml
└── README.md
```