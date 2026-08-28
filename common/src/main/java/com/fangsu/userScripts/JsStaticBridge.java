package com.fangsu.userScripts;

import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class JsStaticBridge {

    public static ProxyObject fromStaticClass(Class<?> clazz) {
        Map<String, Object> map = new HashMap<>();

        // 1. 添加静态字段
        for (Field field : clazz.getFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                try {
                    field.setAccessible(true);
                    map.put(field.getName(), field.get(null));
                } catch (IllegalAccessException e) {
                    // 忽略
                }
            }
        }

        // 2. 按方法名分组，收集所有静态方法（包括重载）
        Map<String, List<Method>> methodsByName = new HashMap<>();
        for (Method method : clazz.getMethods()) {
            if (Modifier.isStatic(method.getModifiers())) {
                methodsByName.computeIfAbsent(method.getName(), k -> new ArrayList<>()).add(method);
            }
        }

        // 3. 为每个方法名创建一个智能代理函数
        for (Map.Entry<String, List<Method>> entry : methodsByName.entrySet()) {
            String methodName = entry.getKey();
            List<Method> overloads = entry.getValue();

            // 注意：ProxyExecutable 的 execute 方法接收的是 Object[]，但我们需要转换成 Value[]
            ProxyExecutable executable = (args) -> {
                // args 是 Object[]，但 GraalJS 传递的每个元素实际上是 Value 对象
                // 需要将 Object[] 转换为 Value[]
                Value[] valueArgs = new Value[args.length];
                for (int i = 0; i < args.length; i++) {
                    // 如果 args[i] 已经是 Value，直接使用；否则需要包装
                    if (args[i] instanceof Value) {
                        valueArgs[i] = (Value) args[i];
                    } else {
                        // 如果传入的是普通 Java 对象，这里可能需要用 Context 创建 Value
                        // 但 GraalJS 通常传递的就是 Value，这个分支可能不会触发
                        throw new IllegalArgumentException("Argument " + i + " is not a Value");
                    }
                }

                // 遍历所有重载，尝试匹配
                Exception lastException = null;
                for (Method method : overloads) {
                    Class<?>[] paramTypes = method.getParameterTypes();
                    // 检查参数数量是否匹配
                    if (paramTypes.length != valueArgs.length) {
                        continue;
                    }
                    try {
                        // 调用你的 convertArgs，将 Value[] 转换为 Java 参数
                        Object[] converted = convertArgs(valueArgs, paramTypes);
                        return method.invoke(null, converted);
                    } catch (IllegalArgumentException | IllegalAccessException e) {
                        // 类型转换失败，尝试下一个重载
                        lastException = e;
                        continue;
                    } catch (Throwable e) {
                        // 业务异常直接抛出
                        throw new RuntimeException("Error invoking " + methodName, e);
                    }
                }
                // 所有重载都不匹配
                throw new IllegalArgumentException(
                        "No suitable overload for " + methodName +
                                " with " + valueArgs.length + " arguments",
                        lastException
                );
            };

            // 方法覆盖同名字段（如果有）
            map.put(methodName, executable);
        }

        return ProxyObject.fromMap(map);
    }

    private static ProxyObject createFieldAndMethodProxy(Object fieldValue, Method method) {
        return new ProxyObject() {
            @Override
            public Object getMember(String key) {
                if ("value".equals(key)) return fieldValue;
                if ("call".equals(key)) return (ProxyExecutable) args -> {
                    try {
                        Object[] converted = convertArgs(args, method.getParameterTypes());
                        return method.invoke(null, converted);
                    } catch (Throwable e) {
                        throw new RuntimeException("Error invoking static method " + method.getName(), e);
                    }
                };
                return null;
            }

            @Override
            public Object getMemberKeys() {
                return new String[]{"value", "call"};
            }

            @Override
            public boolean hasMember(String key) {
                return "value".equals(key) || "call".equals(key);
            }

            @Override
            public void putMember(String key, Value value) {
                throw new UnsupportedOperationException("Cannot modify static field/method");
            }
        };
    }

    private static Object[] convertArgs(Value[] args, Class<?>[] paramTypes) {
        Object[] result = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            if (i < args.length) {
                result[i] = convertValue(args[i], paramTypes[i]);
            } else {
                result[i] = defaultValue(paramTypes[i]);
            }
        }
        return result;
    }

    private static Object convertValue(Value value, Class<?> targetType) {
        if (value == null || value.isNull()) return defaultValue(targetType);

        if (targetType == String.class) {
            try {
                return value.asString();
            } catch (Exception e) {
                return "";
            }
        }
        if (targetType == int.class || targetType == Integer.class) return value.asInt();
        if (targetType == long.class || targetType == Long.class) return value.asLong();
        if (targetType == double.class || targetType == Double.class) return value.asDouble();
        if (targetType == float.class || targetType == Float.class) return (float) value.asDouble();
        if (targetType == boolean.class || targetType == Boolean.class) return value.asBoolean();

        if (value.isHostObject()) {
            Object host = value.asHostObject();
            if (targetType.isInstance(host)) return host;
        }

        try {
            return value.as(targetType);
        } catch (Exception e) {
            return null;
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == String.class) return "";
        if (type == boolean.class || type == Boolean.class) return false;
        if (type == int.class || type == Integer.class) return 0;
        if (type == long.class || type == Long.class) return 0L;
        if (type == float.class || type == Float.class) return 0f;
        if (type == double.class || type == Double.class) return 0d;
        return null;
    }
}