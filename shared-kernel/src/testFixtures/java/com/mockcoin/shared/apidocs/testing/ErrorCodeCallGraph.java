package com.mockcoin.shared.apidocs.testing;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import com.mockcoin.shared.api.ErrorCode;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.Handle;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.asm.Type;

// 컨트롤러 메서드에서 시작해 서비스 코드(com.mockcoin 패키지의 main 클래스)를 바이트코드 수준으로 따라가며
// 도달할 수 있는 ErrorCode enum 상수를 모은다.
// - 인터페이스·가상 호출은 인덱스 안의 모든 구현으로 퍼뜨리고, 람다·메서드 참조는 만들어지는 곳에서 호출된다고 본다(과대 근사).
// - 이벤트 발행·리플렉션·외부 라이브러리 콜백처럼 바이트코드 호출이 아닌 경로는 따라가지 않는다.
// - ErrorCode 없이 만든 ApiException/실패 응답(문서에 나오지 않는 에러)을 만나면 위치를 기록한다.
final class ErrorCodeCallGraph {
    private static final String PACKAGE_PREFIX = "com/mockcoin/";
    private static final String API_EXCEPTION = "com/mockcoin/shared/api/ApiException";
    private static final String API_RESPONSE = "com/mockcoin/shared/api/ApiResponse";
    private static final String RAW_EXCEPTION_DESC = "(ILjava/lang/String;Ljava/lang/String;)V";
    private static final String RAW_FAIL_DESC = "(Ljava/lang/String;Ljava/lang/String;)L" + API_RESPONSE + ";";
    // ErrorCode 없이 에러를 만들어도 되는 곳(다운스트림 전달 relay, 공통 타입 자신)
    private static final Set<String> RAW_ERROR_ALLOWED_OWNERS = Set.of(
            "com/mockcoin/shared/api/ErrorCode", API_EXCEPTION, API_RESPONSE);

    private final Map<String, ClassInfo> classes;
    private final Map<String, Set<String>> subtypes;
    private final ClassLoader classLoader;
    private final Map<String, Boolean> errorCodeOwners = new HashMap<>();

    private ErrorCodeCallGraph(Map<String, ClassInfo> classes, ClassLoader classLoader) {
        this.classes = classes;
        this.classLoader = classLoader;
        this.subtypes = subtypes(classes);
    }

    // 주어진 클래스들이 들어 있는 클래스 경로 루트(디렉터리 또는 jar)의 com.mockcoin 클래스를 모두 읽는다
    static ErrorCodeCallGraph load(Collection<Class<?>> rootMarkers) {
        Map<String, ClassInfo> classes = new HashMap<>();
        Set<Path> roots = new LinkedHashSet<>();
        for (Class<?> marker : rootMarkers) {
            roots.add(codeSource(marker));
        }
        for (Path root : roots) {
            readRoot(root, classes);
        }
        ClassLoader classLoader = rootMarkers.iterator().next().getClassLoader();
        return new ErrorCodeCallGraph(classes, classLoader);
    }

    // 메서드에서 도달할 수 있는 에러 정의(상수 이름 → 정의)와, ErrorCode 없이 에러를 만드는 위치
    Reach reach(Method method) {
        Deque<MethodKey> work = new ArrayDeque<>();
        Set<MethodKey> visited = new HashSet<>();
        Map<String, ErrorCode> errors = new TreeMap<>();
        Set<String> rawErrorSites = new LinkedHashSet<>();
        work.add(new MethodKey(Type.getInternalName(method.getDeclaringClass()), method.getName(), Type.getMethodDescriptor(method)));
        while (!work.isEmpty()) {
            MethodKey key = work.poll();
            if (!visited.add(key)) {
                continue;
            }
            ClassInfo owner = classes.get(key.owner());
            MethodBody body = owner == null ? null : owner.methods().get(key.name() + key.desc());
            if (body == null) {
                continue;
            }
            for (FieldRef field : body.staticReads()) {
                ErrorCode definition = errorConstant(field);
                if (definition != null) {
                    errors.put(definition.name(), definition);
                }
            }
            if (!RAW_ERROR_ALLOWED_OWNERS.contains(key.owner())) {
                body.rawErrors().forEach(line -> rawErrorSites.add(key.owner().replace('/', '.') + "#" + key.name() + line));
            }
            for (Call call : body.calls()) {
                work.addAll(targets(call));
            }
        }
        return new Reach(errors, rawErrorSites);
    }

    record Reach(Map<String, ErrorCode> errors, Set<String> rawErrorSites) {
    }

    private Set<MethodKey> targets(Call call) {
        Set<MethodKey> targets = new LinkedHashSet<>();
        if (!classes.containsKey(call.owner())) {
            return targets;
        }
        MethodKey declared = resolve(call.owner(), call.name(), call.desc());
        if (declared != null) {
            targets.add(declared);
        }
        if (call.virtual()) {
            for (String subtype : subtypes.getOrDefault(call.owner(), Set.of())) {
                ClassInfo info = classes.get(subtype);
                if (info.methods().containsKey(call.name() + call.desc())) {
                    targets.add(new MethodKey(subtype, call.name(), call.desc()));
                }
            }
        }
        return targets;
    }

    // 선언된 클래스를 상위 클래스·인터페이스(default 메서드) 순으로 찾는다
    private MethodKey resolve(String owner, String name, String desc) {
        Deque<String> queue = new ArrayDeque<>(List.of(owner));
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (!seen.add(current)) {
                continue;
            }
            ClassInfo info = classes.get(current);
            if (info == null) {
                continue;
            }
            if (info.methods().containsKey(name + desc)) {
                return new MethodKey(current, name, desc);
            }
            if (info.superName() != null) {
                queue.add(info.superName());
            }
            queue.addAll(info.interfaces());
        }
        return null;
    }

    private ErrorCode errorConstant(FieldRef field) {
        if (!field.desc().equals("L" + field.owner() + ";") || !isErrorCodeEnum(field.owner())) {
            return null;
        }
        try {
            Class<?> type = Class.forName(field.owner().replace('/', '.'), false, classLoader);
            Field constant = type.getField(field.name());
            return constant.isEnumConstant() ? (ErrorCode) constant.get(null) : null;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("에러 상수를 읽지 못했습니다: " + field, exception);
        }
    }

    private boolean isErrorCodeEnum(String owner) {
        return errorCodeOwners.computeIfAbsent(owner, name -> {
            try {
                Class<?> type = Class.forName(name.replace('/', '.'), false, classLoader);
                return type.isEnum() && ErrorCode.class.isAssignableFrom(type);
            } catch (ClassNotFoundException exception) {
                return false;
            }
        });
    }

    private static Map<String, Set<String>> subtypes(Map<String, ClassInfo> classes) {
        Map<String, Set<String>> subtypes = new HashMap<>();
        for (ClassInfo info : classes.values()) {
            Deque<String> ancestors = new ArrayDeque<>();
            if (info.superName() != null) {
                ancestors.add(info.superName());
            }
            ancestors.addAll(info.interfaces());
            Set<String> seen = new HashSet<>();
            while (!ancestors.isEmpty()) {
                String ancestor = ancestors.poll();
                if (!seen.add(ancestor)) {
                    continue;
                }
                subtypes.computeIfAbsent(ancestor, key -> new HashSet<>()).add(info.name());
                ClassInfo ancestorInfo = classes.get(ancestor);
                if (ancestorInfo != null) {
                    if (ancestorInfo.superName() != null) {
                        ancestors.add(ancestorInfo.superName());
                    }
                    ancestors.addAll(ancestorInfo.interfaces());
                }
            }
        }
        return subtypes;
    }

    private static Path codeSource(Class<?> marker) {
        try {
            return Path.of(marker.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException | NullPointerException exception) {
            throw new IllegalStateException("클래스 경로 루트를 찾지 못했습니다: " + marker.getName(), exception);
        }
    }

    private static void readRoot(Path root, Map<String, ClassInfo> classes) {
        try {
            if (Files.isDirectory(root)) {
                if (!Files.isDirectory(root.resolve(PACKAGE_PREFIX))) {
                    return;
                }
                try (Stream<Path> files = Files.walk(root.resolve(PACKAGE_PREFIX))) {
                    for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                        try (InputStream input = Files.newInputStream(file)) {
                            read(input, classes);
                        }
                    }
                }
                return;
            }
            try (JarFile jar = new JarFile(root.toFile())) {
                for (JarEntry entry : java.util.Collections.list(jar.entries())) {
                    if (entry.getName().startsWith(PACKAGE_PREFIX) && entry.getName().endsWith(".class")) {
                        try (InputStream input = jar.getInputStream(entry)) {
                            read(input, classes);
                        }
                    }
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("클래스를 읽지 못했습니다: " + root, exception);
        }
    }

    private static void read(InputStream input, Map<String, ClassInfo> classes) throws IOException {
        ClassReader reader = new ClassReader(input);
        Map<String, MethodBody> methods = new HashMap<>();
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodBody body = new MethodBody(new LinkedHashSet<>(), new ArrayList<>(), new ArrayList<>());
                methods.put(name + descriptor, body);
                return new MethodVisitor(Opcodes.ASM9) {
                    private int line;

                    @Override
                    public void visitLineNumber(int lineNumber, org.springframework.asm.Label start) {
                        line = lineNumber;
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDescriptor) {
                        if (opcode == Opcodes.GETSTATIC && owner.startsWith(PACKAGE_PREFIX)) {
                            body.staticReads().add(new FieldRef(owner, fieldName, fieldDescriptor));
                        }
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName, String methodDescriptor, boolean isInterface) {
                        if (!owner.startsWith(PACKAGE_PREFIX)) {
                            return;
                        }
                        if ((owner.equals(API_EXCEPTION) && methodName.equals("<init>") && methodDescriptor.equals(RAW_EXCEPTION_DESC))
                                || (owner.equals(API_RESPONSE) && methodName.equals("fail") && methodDescriptor.equals(RAW_FAIL_DESC))) {
                            body.rawErrors().add(":" + line);
                        }
                        boolean virtual = opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE;
                        body.calls().add(new Call(owner, methodName, methodDescriptor, virtual));
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String indyName, String indyDescriptor, Handle bootstrap, Object... arguments) {
                        for (Object argument : arguments) {
                            if (argument instanceof Handle handle && handle.getOwner().startsWith(PACKAGE_PREFIX)) {
                                boolean virtual = handle.getTag() == Opcodes.H_INVOKEVIRTUAL || handle.getTag() == Opcodes.H_INVOKEINTERFACE;
                                body.calls().add(new Call(handle.getOwner(), handle.getName(), handle.getDesc(), virtual));
                            }
                        }
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
        classes.put(reader.getClassName(), new ClassInfo(
                reader.getClassName(),
                reader.getSuperName(),
                List.of(reader.getInterfaces()),
                methods));
    }

    private record ClassInfo(String name, String superName, List<String> interfaces, Map<String, MethodBody> methods) {
    }

    private record MethodBody(Set<FieldRef> staticReads, List<Call> calls, List<String> rawErrors) {
    }

    private record MethodKey(String owner, String name, String desc) {
    }

    private record FieldRef(String owner, String name, String desc) {
    }

    private record Call(String owner, String name, String desc, boolean virtual) {
    }
}
