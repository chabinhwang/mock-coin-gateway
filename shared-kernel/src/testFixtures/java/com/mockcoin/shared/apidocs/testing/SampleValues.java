package com.mockcoin.shared.apidocs.testing;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

// 직렬화 이름 비교용으로 record DTO의 표본 인스턴스를 만든다. 모든 필드가 채워지는 것이 목적이다.
// 생성자에서 값을 검증하는 record도 만들 수 있도록, 단순 값 필드는 @Schema(example)가 있으면 그 값을 쓴다.
final class SampleValues {
    private static final int MAX_DEPTH = 4;

    private SampleValues() {
    }

    static Object of(Class<?> recordType) {
        return value(recordType, 0);
    }

    private static Object value(Type type, int depth) {
        Class<?> raw = raw(type);
        if (raw == null) {
            return null;
        }
        if (raw == String.class) {
            return "x";
        }
        if (raw == boolean.class || raw == Boolean.class) {
            return false;
        }
        if (raw == int.class || raw == Integer.class) {
            return 0;
        }
        if (raw == long.class || raw == Long.class) {
            return 0L;
        }
        if (raw == double.class || raw == Double.class) {
            return 0d;
        }
        if (raw == float.class || raw == Float.class) {
            return 0f;
        }
        if (raw == short.class || raw == Short.class) {
            return (short) 0;
        }
        if (raw == byte.class || raw == Byte.class) {
            return (byte) 0;
        }
        if (raw == char.class || raw == Character.class) {
            return 'x';
        }
        if (raw == BigDecimal.class) {
            return BigDecimal.ONE;
        }
        if (raw == BigInteger.class) {
            return BigInteger.ONE;
        }
        if (raw == Instant.class) {
            return Instant.EPOCH;
        }
        if (raw == LocalDate.class) {
            return LocalDate.EPOCH;
        }
        if (raw == LocalDateTime.class) {
            return LocalDateTime.of(1970, 1, 1, 0, 0);
        }
        if (raw == OffsetDateTime.class) {
            return OffsetDateTime.parse("1970-01-01T00:00:00Z");
        }
        if (raw == Duration.class) {
            return Duration.ZERO;
        }
        if (raw == UUID.class) {
            return new UUID(0, 0);
        }
        if (raw == Optional.class) {
            return Optional.empty();
        }
        if (raw.isEnum()) {
            return raw.getEnumConstants()[0];
        }
        if (raw.isArray()) {
            return Array.newInstance(raw.getComponentType(), 0);
        }
        if (Set.class.isAssignableFrom(raw)) {
            return Set.of();
        }
        if (Collection.class.isAssignableFrom(raw)) {
            return List.of();
        }
        if (Map.class.isAssignableFrom(raw)) {
            return Map.of();
        }
        if (raw.isRecord() && depth < MAX_DEPTH) {
            RecordComponent[] components = raw.getRecordComponents();
            Class<?>[] types = new Class<?>[components.length];
            Object[] arguments = new Object[components.length];
            for (int index = 0; index < components.length; index++) {
                types[index] = components[index].getType();
                arguments[index] = exampleOrValue(components[index], depth + 1);
            }
            try {
                Constructor<?> constructor = raw.getDeclaredConstructor(types);
                constructor.setAccessible(true);
                return constructor.newInstance(arguments);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("표본 값을 만들지 못했습니다: " + raw.getName(), exception);
            }
        }
        return null;
    }

    private static Object exampleOrValue(RecordComponent component, int depth) {
        Schema schema = component.getAccessor().getAnnotation(Schema.class);
        if (schema != null && !schema.example().isBlank()) {
            Object example = parseExample(component.getType(), schema.example());
            if (example != null) {
                return example;
            }
        }
        return value(component.getGenericType(), depth);
    }

    private static Object parseExample(Class<?> type, String example) {
        try {
            if (type == String.class) {
                return example;
            }
            if (type == Instant.class) {
                return Instant.parse(example);
            }
            if (type == LocalDate.class) {
                return LocalDate.parse(example);
            }
            if (type == BigDecimal.class) {
                return new BigDecimal(example);
            }
            if (type == int.class || type == Integer.class) {
                return Integer.parseInt(example);
            }
            if (type == long.class || type == Long.class) {
                return Long.parseLong(example);
            }
            if (type == boolean.class || type == Boolean.class) {
                return Boolean.parseBoolean(example);
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        return null;
    }

    private static Class<?> raw(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> clazz) {
            return clazz;
        }
        return null;
    }
}
