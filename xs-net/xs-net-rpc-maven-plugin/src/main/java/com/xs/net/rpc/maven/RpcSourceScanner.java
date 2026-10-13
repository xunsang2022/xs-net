package com.xs.net.rpc.maven;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.apache.maven.plugin.MojoFailureException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

final class RpcSourceScanner {
    /** PO 字段类型到协议记录字段类型的映射，Timestamp 以 int64 毫秒上协议。 */
    private static final Map<String, String> GENERATED_TYPES = Map.of("Timestamp", "Long");

    RpcSourceScanner() {
        StaticJavaParser.getConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
    }

    RpcModel scan(List<Path> sourceDirectories) throws IOException, MojoFailureException {
        List<CompilationUnit> units = parseUnits(sourceDirectories);
        Map<String, RecordSource> recordSources = recordSources(units);
        Map<String, RecordDeclaration> records = new HashMap<>();
        recordSources.forEach((type, source) -> records.put(type, source.record()));
        Map<String, MessageModel> actorModels = actorModelMessages(recordSources);

        // 角色接口：所有标注 @Roles 的接口（如 PlayerMessage、S2C），用于为纯下行消息绑定其角色，从而触发对应 shard 代理的创建
        Set<String> roleInterfaces = new HashSet<>();
        for (CompilationUnit unit : units) {
            String pkg = unit.getPackageDeclaration().map(value -> value.getNameAsString()).orElse("");
            for (ClassOrInterfaceDeclaration type : unit.findAll(ClassOrInterfaceDeclaration.class)) {
                if (type.isInterface() && annotation(type.getAnnotations(), "Roles").isPresent()) {
                    roleInterfaces.add(pkg.isEmpty() ? type.getNameAsString() : pkg + "." + type.getNameAsString());
                }
            }
        }

        // 角色接口与 handler：Actor 自身或其持有的组件可以声明单参数 DTO 处理方法。
        Map<String, SourceType> typesByName = new HashMap<>();
        for (CompilationUnit unit : units) {
            String packageName = unit.getPackageDeclaration().map(value -> value.getNameAsString()).orElse("");
            Map<String, String> imports = imports(unit);
            for (ClassOrInterfaceDeclaration type : unit.findAll(ClassOrInterfaceDeclaration.class)) {
                if (!type.isInterface()) {
                    String typeName = packageName.isEmpty() ? type.getNameAsString()
                            : packageName + "." + type.getNameAsString();
                    typesByName.put(typeName, new SourceType(type, packageName, imports));
                }
            }
        }
        Map<String, MethodBinding> handlerByDto = new HashMap<>();
        for (SourceType sourceType : typesByName.values()) {
            actor(sourceType.declaration(), sourceType.packageName(), sourceType.imports()).ifPresent(actor -> {
                scanHandlerMethods(sourceType, actor, null, records, handlerByDto);
                for (FieldDeclaration field : sourceType.declaration().getFields()) {
                    String componentTypeName = resolveReference(
                            field.getElementType().asString(), sourceType.packageName(), sourceType.imports());
                    SourceType componentType = typesByName.get(componentTypeName);
                    if (componentType == null) {
                        continue;
                    }
                    field.getVariables().forEach(variable -> scanHandlerMethods(
                            componentType, actor, variable.getNameAsString(), records, handlerByDto));
                }
            });
        }

        List<MessageModel> messages = new ArrayList<>();
        Map<String, NestedMessageModel> nestedMessages = new HashMap<>();
        try {
            for (CompilationUnit unit : units) {
                String packageName = unit.getPackageDeclaration().map(value -> value.getNameAsString()).orElse("");
                Map<String, String> imports = imports(unit);
                for (RecordDeclaration record : unit.findAll(RecordDeclaration.class)) {
                    String dtoType = packageName.isEmpty() ? record.getNameAsString() : packageName + "." + record.getNameAsString();
                    annotation(record.getAnnotations(), "Rpc").ifPresent(value -> {
                        try {
                            String kind = enumAttribute(value, "kind");
                            if ("MESSAGE".equals(kind)) {
                                // MESSAGE 类型：尝试查找 handler，如果有则关联
                                MethodBinding binding = handlerByDto.get(dtoType);
                                String roleType = binding != null ? binding.roleType()
                                        : downlinkRole(record, packageName, imports, roleInterfaces);
                                String responseDtoType = binding != null ? binding.responseDtoType() : null;
                                messages.add(message(intAttribute(value, "number"), messageName(dtoType), dtoType,
                                        recordSources, actorModels, nestedMessages, roleType, Direction.HTTP_RESPONSE, true,
                                        binding != null ? binding.actorType() : null,
                                        binding != null ? binding.componentField() : null,
                                        binding != null ? binding.methodName() : null,
                                        responseDtoType, kind));
                                return;
                            }
                            MethodBinding binding = requireHandler(handlerByDto, dtoType, record);
                            requireRole(record, binding.roleType(), packageName, imports);
                            if ("INTERNAL".equals(kind)) {
                                messages.add(message(intAttribute(value, "number"), messageName(dtoType), dtoType,
                                        recordSources, actorModels, nestedMessages, binding.roleType(), Direction.INTERNAL, false,
                                        binding.actorType(), binding.componentField(), binding.methodName(), null, kind));
                                return;
                            }
                            throw new MojoFailureException("Unsupported RPC kind: " + kind);
                        } catch (MojoFailureException exception) {
                            throw new ScanException(exception);
                        }
                    });
                }
            }
        } catch (ScanException exception) {
            throw exception.failure();
        }
        messages.addAll(actorModels.values());
        List<ConverterModel> converters = converters(units, recordSources);
        validate(messages, nestedMessages);
        messages.sort(Comparator.comparingInt(MessageModel::number).thenComparing(MessageModel::name));
        List<GeneratedRecordModel> generatedRecords = recordSources.values().stream()
                .filter(source -> source.generatedSource() != null)
                .map(source -> new GeneratedRecordModel(source.packageName(), source.record().getNameAsString(),
                        source.generatedSource()))
                .sorted(Comparator.comparing(GeneratedRecordModel::typeName)).toList();
        return new RpcModel(List.copyOf(messages), nestedMessages.values().stream()
                .sorted(Comparator.comparing(NestedMessageModel::name)).toList(), List.copyOf(converters), generatedRecords);
    }

    /** 仅扫描 @ProtoRecord 声明的源类型到协议记录类型转换，不解析 RPC 消息。 */
    List<ConverterModel> scanConverters(List<Path> sourceDirectories) throws IOException, MojoFailureException {
        List<CompilationUnit> units = parseUnits(sourceDirectories);
        return converters(units, recordSources(units));
    }

    /** 解析全部 Java 源文件。 */
    private List<CompilationUnit> parseUnits(List<Path> sourceDirectories) throws IOException, MojoFailureException {
        List<CompilationUnit> units = new ArrayList<>();
        for (Path sourceDirectory : sourceDirectories) {
            if (!Files.isDirectory(sourceDirectory)) {
                throw new MojoFailureException("RPC source directory does not exist: " + sourceDirectory);
            }
            try (Stream<Path> paths = Files.walk(sourceDirectory)) {
                for (Path path : paths.filter(value -> value.toString().endsWith(".java")).sorted().toList()) {
                    units.add(StaticJavaParser.parse(path));
                }
            }
        }
        return units;
    }

    /** 收集全部 record 声明与 @ProtoRecord 派生记录及其类型解析上下文。 */
    private Map<String, RecordSource> recordSources(List<CompilationUnit> units) throws MojoFailureException {
        Map<String, RecordSource> recordSources = new HashMap<>();
        for (CompilationUnit unit : units) {
            String packageName = unit.getPackageDeclaration().map(value -> value.getNameAsString() + ".").orElse("");
            String packageTypeName = packageName.endsWith(".")
                    ? packageName.substring(0, packageName.length() - 1)
                    : packageName;
            Map<String, String> imports = imports(unit);
            for (RecordDeclaration record : unit.findAll(RecordDeclaration.class)) {
                recordSources.put(packageName + record.getNameAsString(),
                        new RecordSource(record, packageTypeName, imports, null, null));
            }
        }
        for (CompilationUnit unit : units) {
            String packageName = unit.getPackageDeclaration().map(value -> value.getNameAsString()).orElse("");
            Map<String, String> imports = imports(unit);
            for (ClassOrInterfaceDeclaration type : unit.findAll(ClassOrInterfaceDeclaration.class)) {
                if (type.isInterface()) {
                    continue;
                }
                java.util.Optional<AnnotationExpr> protoRecord = annotation(type.getAnnotations(), "ProtoRecord");
                if (protoRecord.isPresent()) {
                    registerGeneratedRecord(recordSources, packageName, imports, type, protoRecord.get());
                }
            }
        }
        return recordSources;
    }

    /** 依据 @ProtoRecord 的 classType 与源类型 @ProtoField 字段派生协议记录。 */
    private void registerGeneratedRecord(Map<String, RecordSource> recordSources, String packageName,
                                         Map<String, String> imports, ClassOrInterfaceDeclaration type,
                                         AnnotationExpr protoRecord) throws MojoFailureException {
        String sourceType = packageName.isEmpty() ? type.getNameAsString() : packageName + "." + type.getNameAsString();
        String targetType = protoRecordClassType(protoRecord, sourceType, packageName, imports);
        if (recordSources.containsKey(targetType)) {
            // 目标协议记录已存在于源码中，直接复用，不做生成。
            return;
        }
        List<String> components = new ArrayList<>();
        Set<Integer> numbers = new HashSet<>();
        for (FieldDeclaration field : type.getFields()) {
            AnnotationExpr protoField = annotation(field.getAnnotations(), "ProtoField").orElse(null);
            if (protoField == null) {
                continue;
            }
            int number = intAttribute(protoField, "number");
            if (number <= 0 || !numbers.add(number)) {
                throw new MojoFailureException("Invalid or duplicate ProtoField number for " + sourceType + ": " + number);
            }
            String javaType = GENERATED_TYPES.getOrDefault(field.getElementType().asString(),
                    field.getElementType().asString());
            for (var variable : field.getVariables()) {
                components.add("        @ProtoField(number = " + number + ") " + javaType + " "
                        + variable.getNameAsString());
            }
        }
        if (components.isEmpty()) {
            throw new MojoFailureException("@ProtoRecord source type must declare @ProtoField fields: " + sourceType);
        }
        int separator = targetType.lastIndexOf('.');
        String targetPackage = separator < 0 ? "" : targetType.substring(0, separator);
        String targetSimpleName = targetType.substring(separator + 1);
        String source = recordSource(targetPackage, targetSimpleName, components, sourceType,
                intAttribute(protoRecord, "number"));
        RecordDeclaration record = StaticJavaParser.parse(source).findFirst(RecordDeclaration.class)
                .orElseThrow(() -> new IllegalStateException("Failed to generate protocol record: " + targetType));
        recordSources.put(targetType, new RecordSource(record, targetPackage, Map.of(), source,
                intAttribute(protoRecord, "number")));
    }

    /** 生成协议记录源码文本，Timestamp 字段映射为 int64 毫秒。 */
    private String recordSource(String targetPackage, String targetSimpleName, List<String> components,
                                String sourceType, int number) {
        StringBuilder source = new StringBuilder();
        if (!targetPackage.isEmpty()) {
            source.append("package ").append(targetPackage).append(";\n\n");
        }
        source.append("import com.game.message.annotation.ProtoField;\n");
        source.append("import com.game.message.annotation.Rpc;\n\n");
        source.append("/** 由 ").append(sourceType).append(" 的 @ProtoRecord 自动生成的协议数据。 */\n");
        source.append("@Rpc(kind = Rpc.Kind.ACTOR_MODEL, number = ").append(number).append(")\n");
        source.append("public record ").append(targetSimpleName).append("(\n");
        source.append(String.join(",\n", components)).append("\n) {\n}\n");
        return source.toString();
    }

    /** 读取 @ProtoRecord(classType) 声明的协议记录全限定类名。 */
    private String protoRecordClassType(AnnotationExpr protoRecord, String sourceType, String packageName,
                                        Map<String, String> imports) throws MojoFailureException {
        Expression classType = attribute(protoRecord, "classType");
        if (!(classType instanceof StringLiteralExpr literal) || literal.asString().isBlank()) {
            throw new MojoFailureException("@ProtoRecord classType must be a non-blank class name: " + sourceType);
        }
        String declared = literal.asString();
        return declared.contains(".") ? declared : resolveReference(declared, packageName, imports);
    }

    /** 收集全部 @ProtoRecord 声明的转换。 */
    private List<ConverterModel> converters(List<CompilationUnit> units, Map<String, RecordSource> recordSources)
            throws MojoFailureException {
        List<ConverterModel> converters = new ArrayList<>();
        for (CompilationUnit unit : units) {
            String packageName = unit.getPackageDeclaration().map(value -> value.getNameAsString()).orElse("");
            Map<String, String> imports = imports(unit);
            for (ClassOrInterfaceDeclaration type : unit.findAll(ClassOrInterfaceDeclaration.class)) {
                if (type.isInterface()) {
                    continue;
                }
                java.util.Optional<AnnotationExpr> protoRecord = annotation(type.getAnnotations(), "ProtoRecord");
                if (protoRecord.isPresent()) {
                    converters.add(converter(packageName, imports, type, protoRecord.get(), recordSources));
                }
            }
        }
        return converters;
    }

    /** 读取 @ProtoRecord(classType) 声明的源类型到协议记录字段映射。 */
    private ConverterModel converter(String packageName, Map<String, String> imports, ClassOrInterfaceDeclaration type,
                                     AnnotationExpr protoRecord, Map<String, RecordSource> recordSources)
            throws MojoFailureException {
        String sourceType = packageName.isEmpty() ? type.getNameAsString() : packageName + "." + type.getNameAsString();
        String targetType = protoRecordClassType(protoRecord, sourceType, packageName, imports);
        RecordSource target = requireRecordSource(recordSources, targetType);
        Map<String, String> sourceFieldTypes = new HashMap<>();
        for (FieldDeclaration field : type.getFields()) {
            field.getVariables().forEach(variable ->
                    sourceFieldTypes.put(variable.getNameAsString(), field.getElementType().asString()));
        }
        List<ConverterFieldModel> fields = new ArrayList<>();
        for (var component : target.record().getParameters()) {
            String name = component.getNameAsString();
            String sourceFieldType = sourceFieldTypes.get(name);
            if (sourceFieldType == null) {
                throw new MojoFailureException("@ProtoRecord source field is missing: " + sourceType + "." + name);
            }
            fields.add(new ConverterFieldModel(name, accessExpression(sourceType, sourceFieldType, component.getTypeAsString(), name)));
        }
        if (fields.isEmpty()) {
            throw new MojoFailureException("@ProtoRecord target record must declare at least one field: " + targetType);
        }
        return new ConverterModel(sourceType, targetType, target.record().getNameAsString(), List.copyOf(fields));
    }

    /** 生成源字段到协议字段的取值表达式，仅支持同类型与 Timestamp→long 毫秒转换。 */
    private String accessExpression(String sourceType, String sourceFieldType, String targetFieldType, String name)
            throws MojoFailureException {
        String getter = ("boolean".equals(sourceFieldType) ? "is" : "get") + upper(name) + "()";
        if (sourceFieldType.equals(targetFieldType)) {
            return "source." + getter;
        }
        if ("Timestamp".equals(sourceFieldType) && ("Long".equals(targetFieldType) || "long".equals(targetFieldType))) {
            return "source." + getter + " == null ? null : source." + getter + ".getTime()";
        }
        throw new MojoFailureException("@ProtoRecord field conversion is unsupported: " + sourceType + "." + name
                + " (" + sourceFieldType + " -> " + targetFieldType + ")");
    }

    private MethodBinding preferBinding(MethodBinding current, MethodBinding candidate) {
        if (current.responseDtoType() == null && candidate.responseDtoType() != null) {
            return candidate;
        }
        return current;
    }

    private MethodBinding requireHandler(Map<String, MethodBinding> handlerByDto, String dtoType, RecordDeclaration record)
            throws MojoFailureException {
        MethodBinding binding = handlerByDto.get(dtoType);
        if (binding == null) {
            throw new MojoFailureException("No actor method handled message DTO "
                    + dtoType + "; a handler in an actor extending RpcActor<Role> with single parameter of this type is required");
        }
        return binding;
    }

    private void requireRole(RecordDeclaration record, String roleType, String packageName, Map<String, String> imports)
            throws MojoFailureException {
        if (roleType == null) {
            throw new MojoFailureException("RPC DTO must implement a registered role interface: " + record.getNameAsString());
        }
        Set<String> implemented = new HashSet<>();
        for (ClassOrInterfaceType implementedType : record.getImplementedTypes()) {
            implemented.add(resolveReference(implementedType.getNameAsString(), packageName, imports));
        }
        if (!implemented.contains(roleType)) {
            throw new MojoFailureException("RPC DTO must implement role interface " + roleType + ": "
                    + record.getNameAsString());
        }
    }

    /**
     * 纯下行（MESSAGE）消息若实现了某个 @Roles 角色接口（如 S2C），则绑定该角色，
     * 使生成规格携带 roleClass，从而在非宿主节点创建对应的 shard 代理。
     */
    private String downlinkRole(RecordDeclaration record, String packageName, Map<String, String> imports, Set<String> roleInterfaces) {
        for (ClassOrInterfaceType implemented : record.getImplementedTypes()) {
            String resolved = resolveReference(implemented.getNameAsString(), packageName, imports);
            if (roleInterfaces.contains(resolved)) {
                return resolved;
            }
        }
        return null;
    }

    /** 扫描 Actor 或其一个字段组件中，以 RPC DTO 为唯一参数的方法。 */
    private void scanHandlerMethods(
            SourceType sourceType,
            ActorModel actor,
            String componentField,
            Map<String, RecordDeclaration> records,
            Map<String, MethodBinding> handlerByDto
    ) {
        for (MethodDeclaration method : sourceType.declaration().getMethods()) {
            if (method.getParameters().size() != 1) {
                continue;
            }
            String dtoType;
            try {
                dtoType = resolveType(method.getParameter(0).getTypeAsString(), sourceType.packageName(),
                        sourceType.imports(), records);
            } catch (MojoFailureException exception) {
                // 非 DTO 方法参数（如 Actor 内部消息或生命周期辅助方法）不参与 RPC 路由生成。
                continue;
            }
            String responseDtoType;
            try {
                responseDtoType = method.getType().isVoidType() ? null
                        : resolveType(method.getTypeAsString(), sourceType.packageName(), sourceType.imports(), records);
            } catch (MojoFailureException exception) {
                responseDtoType = null;
            }
            MethodBinding binding = new MethodBinding(
                    actor.actorType(), actor.roleType(), componentField, method.getNameAsString(), responseDtoType);
            handlerByDto.merge(dtoType, binding, this::preferBinding);
        }
    }

    private record MethodBinding(
            String actorType,
            String roleType,
            String componentField,
            String methodName,
            String responseDtoType
    ) {
    }

    private MessageModel message(int number, String name, String dtoType,
                                 Map<String, RecordSource> recordSources, Map<String, MessageModel> actorModels,
                                 Map<String, NestedMessageModel> nestedMessages, String roleType, Direction direction,
                                 boolean publicVisible, String actorType, String handlerComponent, String handlerMethod,
                                 String responseDtoType, String kind) throws MojoFailureException {
        if (number <= 0 || name.isBlank()) {
            throw new MojoFailureException("RPC number must be positive and name must not be blank: " + dtoType);
        }
        List<FieldModel> fields = fields(dtoType, recordSources, actorModels, nestedMessages, true);
        return new MessageModel(number, name, dtoType, List.copyOf(fields), roleType, direction, publicVisible, actorType,
                handlerComponent, handlerMethod, responseDtoType, kind);
    }

    /** 依据 @ProtoRecord 声明，为协议记录生成 ACTOR_MODEL 消息。 */
    private Map<String, MessageModel> actorModelMessages(Map<String, RecordSource> recordSources)
            throws MojoFailureException {
        Map<String, MessageModel> actorModels = new LinkedHashMap<>();
        for (RecordSource source : recordSources.values()) {
            if (source.number() == null) {
                continue;
            }
            String dtoType = source.packageName().isEmpty() ? source.record().getNameAsString()
                    : source.packageName() + "." + source.record().getNameAsString();
            List<FieldModel> fields = fields(dtoType, recordSources, actorModels, new HashMap<>(), false);
            actorModels.put(dtoType, new MessageModel(source.number(), messageName(dtoType), dtoType, fields,
                    null, Direction.HTTP_RESPONSE, true, null, null, null, null, "ACTOR_MODEL"));
        }
        return actorModels;
    }

    private List<FieldModel> fields(String dtoType, Map<String, RecordSource> recordSources,
                                    Map<String, MessageModel> actorModels,
                                    Map<String, NestedMessageModel> nestedMessages, boolean allowNested)
            throws MojoFailureException {
        RecordSource source = requireRecordSource(recordSources, dtoType);
        List<FieldModel> fields = new ArrayList<>();
        Set<Integer> numbers = new HashSet<>();
        for (var component : source.record().getParameters()) {
            AnnotationExpr protoField = annotation(component.getAnnotations(), "ProtoField")
                    .orElseThrow(() -> new MojoFailureException("Record component requires @ProtoField: " + dtoType + "." + component.getNameAsString()));
            int number = intAttribute(protoField, "number");
            if (number <= 0 || !numbers.add(number)) {
                throw new MojoFailureException("Invalid or duplicate ProtoField number for " + dtoType + ": " + number);
            }
            String javaType = component.getTypeAsString();
            String nestedType = null;
            String messageType = null;
            String messageCodec = null;
            if (!Set.of("String", "Integer", "int", "Long", "long", "Boolean", "boolean",
                    "Double", "double", "Float", "float", "byte[]").contains(javaType)) {
                String resolved = resolveReference(javaType, source.packageName(), source.imports());
                MessageModel actorModel = actorModels.get(resolved);
                if (actorModel != null) {
                    // 已注册为 ACTOR_MODEL 消息的类型，直接以消息类型引用，不重复生成嵌套定义。
                    messageType = actorModel.name();
                    messageCodec = "codec" + actorModel.number();
                    javaType = resolved;
                } else {
                    RecordSource nestedSource = recordSources.get(resolved);
                    if (nestedSource == null || annotation(nestedSource.record().getAnnotations(), "Rpc").isPresent()) {
                        throw new MojoFailureException("Unsupported @ProtoField type: " + javaType + " in " + dtoType);
                    }
                    if (!allowNested) {
                        throw new MojoFailureException("Unsupported nested @ProtoField depth: " + dtoType + "." + component.getNameAsString());
                    }
                    nestedType = "Nested" + nestedSource.record().getNameAsString();
                    if (!nestedMessages.containsKey(resolved)) {
                        List<FieldModel> nestedFields = fields(resolved, recordSources, actorModels, nestedMessages, false);
                        nestedMessages.put(resolved, new NestedMessageModel(nestedType, resolved, List.copyOf(nestedFields)));
                    }
                    javaType = resolved;
                }
            }
            fields.add(new FieldModel(number, component.getNameAsString(), javaType, nestedType, messageType, messageCodec));
        }
        if (fields.isEmpty()) {
            throw new MojoFailureException("Protocol record must declare at least one field: " + dtoType);
        }
        return fields;
    }

    private void validate(List<MessageModel> messages, Map<String, NestedMessageModel> nestedMessages) throws MojoFailureException {
        Set<Integer> numbers = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (MessageModel message : messages) {
            if (!numbers.add(message.number())) {
                throw new MojoFailureException("Duplicate RPC number: " + message.number());
            }
            if (!names.add(message.name())) {
                throw new MojoFailureException("Duplicate RPC name: " + message.name());
            }
            if (message.direction() == Direction.INTERNAL
                    && (message.roleType() == null || message.actorType() == null || message.handlerMethod() == null)) {
                throw new MojoFailureException("RPC handler must belong to an actor extending RpcActor<Role>: " + message.name());
            }
        }
        for (NestedMessageModel nested : nestedMessages.values()) {
            if (!names.add(nested.name())) {
                throw new MojoFailureException("Duplicate proto message name: " + nested.name());
            }
        }
    }

    static String protoType(String javaType) throws MojoFailureException {
        return switch (javaType) {
            case "String" -> "string";
            case "Integer", "int" -> "int32";
            case "Long", "long" -> "int64";
            case "Boolean", "boolean" -> "bool";
            case "Double", "double" -> "double";
            case "Float", "float" -> "float";
            case "byte[]" -> "bytes";
            default -> throw new MojoFailureException("Unsupported @ProtoField type: " + javaType);
        };
    }

    private RecordSource requireRecordSource(Map<String, RecordSource> recordSources, String type)
            throws MojoFailureException {
        RecordSource source = recordSources.get(type);
        if (source == null) {
            throw new MojoFailureException("RPC DTO must be a scanned Java record: " + type);
        }
        return source;
    }

    private String resolveType(String type, String packageName, Map<String, String> imports, Map<String, RecordDeclaration> records)
            throws MojoFailureException {
        if (type.contains("<") || type.contains("[")) {
            throw new MojoFailureException("RPC DTO type must not be generic or array: " + type);
        }
        if (records.containsKey(type)) {
            return type;
        }
        String imported = imports.get(type);
        if (imported != null) {
            return imported;
        }
        String local = packageName.isEmpty() ? type : packageName + "." + type;
        if (records.containsKey(local)) {
            return local;
        }
        throw new MojoFailureException("Cannot resolve RPC DTO type: " + type);
    }

    private java.util.Optional<ActorModel> actor(ClassOrInterfaceDeclaration actor, String packageName, Map<String, String> imports) {
        ClassOrInterfaceType parent = actor.getExtendedTypes().stream()
                .filter(value -> value.getNameAsString().equals("RpcActor"))
                .findFirst()
                .orElse(null);
        if (parent == null || parent.getTypeArguments().isEmpty() || parent.getTypeArguments().get().size() != 1) {
            return java.util.Optional.empty();
        }
        String roleType = resolveReference(parent.getTypeArguments().get().get(0).asString(), packageName, imports);
        String actorType = packageName.isEmpty() ? actor.getNameAsString() : packageName + "." + actor.getNameAsString();
        return java.util.Optional.of(new ActorModel(actorType, roleType));
    }

    private String resolveReference(String type, String packageName, Map<String, String> imports) {
        if (type.contains(".")) {
            return type;
        }
        return imports.getOrDefault(type, packageName.isEmpty() ? type : packageName + "." + type);
    }

    private Map<String, String> imports(CompilationUnit unit) {
        Map<String, String> result = new HashMap<>();
        unit.getImports().forEach(value -> {
            if (!value.isAsterisk()) {
                String name = value.getNameAsString();
                result.put(name.substring(name.lastIndexOf('.') + 1), name);
            }
        });
        return result;
    }

    private java.util.Optional<AnnotationExpr> annotation(List<AnnotationExpr> annotations, String simpleName) {
        return annotations.stream().filter(value -> value.getName().getIdentifier().equals(simpleName)).findFirst();
    }

    private String messageName(String dtoType) {
        String simpleName = dtoType.substring(dtoType.lastIndexOf('.') + 1);
        if (simpleName.endsWith("Request")) {
            String baseName = simpleName.substring(0, simpleName.length() - "Request".length());
            return baseName.isEmpty() ? simpleName : "CS_" + baseName;
        }
        if (simpleName.endsWith("Response")) {
            String baseName = simpleName.substring(0, simpleName.length() - "Response".length());
            return baseName.isEmpty() ? simpleName : "SC_" + baseName;
        }
        return simpleName;
    }

    private String upper(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private int intAttribute(AnnotationExpr annotation, String name) throws MojoFailureException {
        try {
            return Integer.parseInt(attribute(annotation, name).toString());
        } catch (NumberFormatException exception) {
            throw new MojoFailureException("Annotation attribute must be an integer: " + annotation + "." + name, exception);
        }
    }

    private String enumAttribute(AnnotationExpr annotation, String name) throws MojoFailureException {
        String value = attribute(annotation, name).toString();
        int separator = value.lastIndexOf('.');
        return separator < 0 ? value : value.substring(separator + 1);
    }

    private Expression attribute(AnnotationExpr annotation, String name) throws MojoFailureException {
        if (annotation instanceof SingleMemberAnnotationExpr single && name.equals("value")) {
            return single.getMemberValue();
        }
        if (annotation instanceof NormalAnnotationExpr normal) {
            return normal.getPairs().stream().filter(value -> value.getNameAsString().equals(name)).map(MemberValuePair::getValue)
                    .findFirst().orElseThrow(() -> new MojoFailureException("Missing annotation attribute " + name + ": " + annotation));
        }
        throw new MojoFailureException("Missing annotation attribute " + name + ": " + annotation);
    }

    private record ActorModel(String actorType, String roleType) {
    }

    /** 保存类声明及其源文件的类型解析上下文。 */
    private record SourceType(
            ClassOrInterfaceDeclaration declaration,
            String packageName,
            Map<String, String> imports
    ) {
    }

    /** 保存 record 声明及其源文件的类型解析上下文。 */
    private record RecordSource(
            RecordDeclaration record,
            String packageName,
            Map<String, String> imports,
            /** 非空表示该记录由 @ProtoRecord 生成，需要落地为源码文件。 */
            String generatedSource,
            /** @ProtoRecord 声明的 RPC 编号，仅生成记录有值。 */
            Integer number
    ) {
    }

    private static final class ScanException extends RuntimeException {
        private final MojoFailureException failure;

        private ScanException(MojoFailureException failure) {
            this.failure = failure;
        }

        private MojoFailureException failure() {
            return failure;
        }
    }
}
