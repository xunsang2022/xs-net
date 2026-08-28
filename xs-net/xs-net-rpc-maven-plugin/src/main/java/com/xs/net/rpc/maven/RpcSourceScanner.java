package com.xs.net.rpc.maven;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.apache.maven.plugin.MojoFailureException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

final class RpcSourceScanner {
    RpcSourceScanner() {
        StaticJavaParser.getConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
    }

    RpcModel scan(List<Path> sourceDirectories) throws IOException, MojoFailureException {
        Map<String, RecordDeclaration> records = new HashMap<>();
        List<CompilationUnit> units = new ArrayList<>();
        for (Path sourceDirectory : sourceDirectories) {
            if (!Files.isDirectory(sourceDirectory)) {
                throw new MojoFailureException("RPC source directory does not exist: " + sourceDirectory);
            }
            try (Stream<Path> paths = Files.walk(sourceDirectory)) {
                for (Path path : paths.filter(value -> value.toString().endsWith(".java")).sorted().toList()) {
                    CompilationUnit unit = StaticJavaParser.parse(path);
                    units.add(unit);
                    String packageName = unit.getPackageDeclaration().map(value -> value.getNameAsString() + ".").orElse("");
                    for (RecordDeclaration record : unit.findAll(RecordDeclaration.class)) {
                        records.put(packageName + record.getNameAsString(), record);
                    }
                }
            }
        }

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

        // 角色接口与 handler：凡直接继承 RpcActor<Role> 的类，按其单参数类型匹配 DTO
        Map<ClassOrInterfaceDeclaration, ActorModel> actorByClass = new HashMap<>();
        Map<String, MethodBinding> handlerByDto = new HashMap<>();
        for (CompilationUnit unit : units) {
            String packageName = unit.getPackageDeclaration().map(value -> value.getNameAsString()).orElse("");
            Map<String, String> imports = imports(unit);
            for (ClassOrInterfaceDeclaration type : unit.findAll(ClassOrInterfaceDeclaration.class)) {
                if (type.isInterface()) {
                    continue;
                }
                actor(type, packageName, imports).ifPresent(actor -> {
                    actorByClass.put(type, actor);
                    for (MethodDeclaration method : type.getMethods()) {
                        if (method.getParameters().size() != 1) {
                            continue;
                        }
                        String dtoType;
                        try {
                            dtoType = resolveType(method.getParameter(0).getTypeAsString(), packageName, imports, records);
                        } catch (MojoFailureException exception) {
                            // 非 DTO 方法参数（如 RpcMessage、Actor 内部类/信号），跳过
                            continue;
                        }
                        String responseDtoType;
                        try {
                            responseDtoType = method.getType().isVoidType() ? null
                                    : resolveType(method.getTypeAsString(), packageName, imports, records);
                        } catch (MojoFailureException exception) {
                            responseDtoType = null;
                        }
                        MethodBinding binding = new MethodBinding(
                                actor.actorType(), actor.roleType(), method.getNameAsString(), responseDtoType);
                        handlerByDto.merge(dtoType, binding, (current, candidate) ->
                                preferBinding(current, candidate));
                    }
                });
            }
        }

        List<MessageModel> messages = new ArrayList<>();
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
                                messages.add(message(intAttribute(value, "number"), messageName(dtoType), dtoType,
                                        record, downlinkRole(record, packageName, imports, roleInterfaces), Direction.HTTP_RESPONSE, true, null, null, null));
                                return;
                            }
                            MethodBinding binding = requireHandler(handlerByDto, dtoType, record);
                            requireRole(record, binding.roleType(), packageName, imports);
                            if ("INTERNAL".equals(kind)) {
                                messages.add(message(intAttribute(value, "number"), messageName(dtoType), dtoType,
                                        record, binding.roleType(), Direction.INTERNAL, false, binding.actorType(), binding.methodName(), null));
                                return;
                            }
                            int requestNumber = intAttribute(value, "requestNumber");
                            int responseNumber = intAttribute(value, "responseNumber");
                            String responseDtoType = requireResponse(binding, dtoType, record);
                            messages.add(message(requestNumber, messageName(dtoType), dtoType, record,
                                    binding.roleType(), Direction.REQUEST, true, binding.actorType(), binding.methodName(), responseDtoType));
                            messages.add(message(responseNumber, messageName(responseDtoType), responseDtoType,
                                    requireRecord(records, responseDtoType), binding.roleType(), Direction.RESPONSE, true,
                                    null, null, null));
                        } catch (MojoFailureException exception) {
                            throw new ScanException(exception);
                        }
                    });
                }
            }
        } catch (ScanException exception) {
            throw exception.failure();
        }
        validate(messages);
        messages.sort(Comparator.comparingInt(MessageModel::number).thenComparing(MessageModel::name));
        return new RpcModel(List.copyOf(messages));
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

    private String requireResponse(MethodBinding binding, String dtoType, RecordDeclaration record) throws MojoFailureException {
        if (binding.responseDtoType() == null) {
            throw new MojoFailureException("@Rpc handler must return a response DTO that is a scanned record: "
                    + dtoType);
        }
        return binding.responseDtoType();
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

    private record MethodBinding(String actorType, String roleType, String methodName, String responseDtoType) {
    }

    private MessageModel message(int number, String name, String dtoType, RecordDeclaration record, String roleType,
                                 Direction direction, boolean publicVisible, String actorType, String handlerMethod,
                                 String responseDtoType) throws MojoFailureException {
        if (number <= 0 || name.isBlank()) {
            throw new MojoFailureException("RPC number must be positive and name must not be blank: " + dtoType);
        }
        List<FieldModel> fields = new ArrayList<>();
        for (var component : record.getParameters()) {
            AnnotationExpr protoField = annotation(component.getAnnotations(), "ProtoField")
                    .orElseThrow(() -> new MojoFailureException("Record component requires @ProtoField: " + dtoType + "." + component.getNameAsString()));
            fields.add(new FieldModel(intAttribute(protoField, "number"), component.getNameAsString(), component.getTypeAsString()));
        }
        if (fields.isEmpty()) {
            throw new MojoFailureException("Protocol record must declare at least one field: " + dtoType);
        }
        fields.sort(Comparator.comparingInt(FieldModel::number));
        return new MessageModel(number, name, dtoType, List.copyOf(fields), roleType, direction, publicVisible, actorType,
                handlerMethod, responseDtoType);
    }

    private void validate(List<MessageModel> messages) throws MojoFailureException {
        Set<Integer> numbers = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (MessageModel message : messages) {
            if (!numbers.add(message.number())) {
                throw new MojoFailureException("Duplicate RPC number: " + message.number());
            }
            if (!names.add(message.name())) {
                throw new MojoFailureException("Duplicate RPC name: " + message.name());
            }
            Set<Integer> fields = new HashSet<>();
            for (FieldModel field : message.fields()) {
                if (field.number() <= 0 || !fields.add(field.number())) {
                    throw new MojoFailureException("Invalid or duplicate ProtoField number for " + message.name() + ": " + field.number());
                }
                protoType(field.javaType());
            }
            if ((message.direction() == Direction.REQUEST || message.direction() == Direction.INTERNAL)
                    && (message.roleType() == null || message.actorType() == null || message.handlerMethod() == null)) {
                throw new MojoFailureException("RPC handler must belong to an actor extending RpcActor<Role>: " + message.name());
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

    private RecordDeclaration requireRecord(Map<String, RecordDeclaration> records, String type) throws MojoFailureException {
        RecordDeclaration record = records.get(type);
        if (record == null) {
            throw new MojoFailureException("RPC DTO must be a scanned Java record: " + type);
        }
        return record;
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
