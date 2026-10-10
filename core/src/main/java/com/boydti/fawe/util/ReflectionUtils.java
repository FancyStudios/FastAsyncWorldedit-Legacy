package com.boydti.fawe.util;

import sun.misc.Unsafe;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * @author DPOH-VAR
 * @version 1.0
 */
@SuppressWarnings({"UnusedDeclaration", "rawtypes"})
public class ReflectionUtils {
    private static final Unsafe UNSAFE;

    static {
        try {
            Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            UNSAFE = (Unsafe) theUnsafe.get(null);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static <T> T as(Class<T> t, Object o) {
        return t.isInstance(o) ? t.cast(o) : null;
    }

    @SuppressWarnings("unchecked")
    public static <T extends Enum<?>> T addEnum(Class<T> enumType, String enumName) {
        Field valuesField = getValuesField(enumType);
        try {
            // 1. Copy the previous enum instances
            T[] previousValues = (T[]) valuesField.get(null);
            List<T> values = new ArrayList<>(Arrays.asList(previousValues));

            // 2. Build the new enum constant without running a constructor
            T newValue = (T) UNSAFE.allocateInstance(enumType);
            setFailsafeFieldValue(Enum.class.getDeclaredField("name"), newValue, enumName);
            setFailsafeFieldValue(Enum.class.getDeclaredField("ordinal"), newValue, values.size());

            // 3. Add it and replace $VALUES
            values.add(newValue);
            setFailsafeFieldValue(valuesField, null, values.toArray((T[]) Array.newInstance(enumType, 0)));

            // 4. Clean the enum caches held by java.lang.Class
            cleanEnumCache(enumType);
            return newValue;
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public static <T extends Enum<?>> void clearEnum(Class<T> enumType) {
        Field valuesField = getValuesField(enumType);
        try {
            setFailsafeFieldValue(valuesField, null, Array.newInstance(enumType, 0));
            cleanEnumCache(enumType);
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    private static Field getValuesField(Class<?> enumType) {
        if (!Enum.class.isAssignableFrom(enumType)) {
            throw new RuntimeException("class " + enumType + " is not an instance of Enum");
        }
        for (Field field : enumType.getDeclaredFields()) {
            if (field.getName().contains("$VALUES")) {
                field.setAccessible(true);
                return field;
            }
        }
        throw new RuntimeException("class " + enumType + " has no $VALUES field");
    }

    /**
     * Sets a field even if it is final or inaccessible (e.g. a field of a java.base class).
     * Final fields and fields that cannot be made accessible are written through Unsafe.
     */
    public static void setFailsafeFieldValue(Field field, Object target, Object value)
            throws NoSuchFieldException, IllegalAccessException {
        boolean isStatic = Modifier.isStatic(field.getModifiers());
        if (!Modifier.isFinal(field.getModifiers())) {
            try {
                field.setAccessible(true);
                field.set(isStatic ? null : target, value);
                return;
            } catch (RuntimeException ignore) {
                // InaccessibleObjectException: module not opened to us, fall through to Unsafe
            }
        }
        Class<?> type = field.getType();
        if (type.isPrimitive() ? value == null : value != null && !type.isInstance(value)) {
            throw new IllegalArgumentException("Cannot set " + field + " to " + value);
        }
        Object base = isStatic ? UNSAFE.staticFieldBase(field) : Objects.requireNonNull(target, "target");
        long offset = isStatic ? UNSAFE.staticFieldOffset(field) : UNSAFE.objectFieldOffset(field);
        if (!type.isPrimitive()) UNSAFE.putObjectVolatile(base, offset, value);
        else if (type == int.class) UNSAFE.putIntVolatile(base, offset, ((Number) value).intValue());
        else if (type == long.class) UNSAFE.putLongVolatile(base, offset, ((Number) value).longValue());
        else if (type == boolean.class) UNSAFE.putBooleanVolatile(base, offset, (Boolean) value);
        else if (type == byte.class) UNSAFE.putByteVolatile(base, offset, ((Number) value).byteValue());
        else if (type == short.class) UNSAFE.putShortVolatile(base, offset, ((Number) value).shortValue());
        else if (type == char.class) UNSAFE.putCharVolatile(base, offset, (Character) value);
        else if (type == float.class) UNSAFE.putFloatVolatile(base, offset, ((Number) value).floatValue());
        else if (type == double.class) UNSAFE.putDoubleVolatile(base, offset, ((Number) value).doubleValue());
    }

    private static void cleanEnumCache(Class<?> enumClass)
            throws NoSuchFieldException, IllegalAccessException {
        setFailsafeFieldValue(Class.class.getDeclaredField("enumConstantDirectory"), enumClass, null);
        setFailsafeFieldValue(Class.class.getDeclaredField("enumConstants"), enumClass, null);
    }

    private static Class<?> UNMODIFIABLE_MAP = Collections.unmodifiableMap(Collections.EMPTY_MAP).getClass();

    public static <T, V> Map<T, V> getMap(Map<T, V> map) {
        try {
            Class<? extends Map> clazz = map.getClass();
            if (clazz != UNMODIFIABLE_MAP) return map;
            Field m = clazz.getDeclaredField("m");
            return (Map<T, V>) UNSAFE.getObject(map, UNSAFE.objectFieldOffset(m));
        } catch (Throwable e) {
            MainUtil.handleError(e);
            return map;
        }
    }

    public static <T> List<T> getList(List<T> list) {
        try {
            Class<? extends List> clazz = (Class<? extends List>) Class.forName("java.util.Collections$UnmodifiableList");
            if (!clazz.isInstance(list)) return list;
            Field m = clazz.getDeclaredField("list");
            return (List<T>) UNSAFE.getObject(list, UNSAFE.objectFieldOffset(m));
        } catch (Throwable e) {
            MainUtil.handleError(e);
            return list;
        }
    }

    public static Object getHandle(final Object wrapper) {
        final Method getHandle = makeMethod(wrapper.getClass(), "getHandle");
        return callMethod(getHandle, wrapper);
    }

    //Utils
    public static Method makeMethod(final Class<?> clazz, final String methodName, final Class<?>... paramaters) {
        try {
            return clazz.getDeclaredMethod(methodName, paramaters);
        } catch (final NoSuchMethodException ex) {
            return null;
        } catch (final Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> T callMethod(final Method method, final Object instance, final Object... paramaters) {
        if (method == null) {
            throw new RuntimeException("No such method");
        }
        method.setAccessible(true);
        try {
            return (T) method.invoke(instance, paramaters);
        } catch (final InvocationTargetException ex) {
            throw new RuntimeException(ex.getCause());
        } catch (final Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> Constructor<T> makeConstructor(final Class<?> clazz, final Class<?>... paramaterTypes) {
        try {
            return (Constructor<T>) clazz.getConstructor(paramaterTypes);
        } catch (final NoSuchMethodException ex) {
            return null;
        } catch (final Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public static <T> T callConstructor(final Constructor<T> constructor, final Object... paramaters) {
        if (constructor == null) {
            throw new RuntimeException("No such constructor");
        }
        constructor.setAccessible(true);
        try {
            return constructor.newInstance(paramaters);
        } catch (final InvocationTargetException ex) {
            throw new RuntimeException(ex.getCause());
        } catch (final Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public static Field makeField(final Class<?> clazz, final String name) {
        try {
            return clazz.getDeclaredField(name);
        } catch (final NoSuchFieldException ex) {
            return null;
        } catch (final Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public static Field findField(final Class<?> clazz, final Class<?> type, int hasMods, int noMods) {
        for (Field field : clazz.getDeclaredFields()) {
            if (type == null || type.isAssignableFrom(field.getType())) {
                int mods = field.getModifiers();
                if ((mods & hasMods) == hasMods && (mods & noMods) == 0) {
                    return setAccessible(field);
                }
            }
        }
        return null;
    }

    public static Field findField(final Class<?> clazz, final Class<?> type) {
        for (Field field : clazz.getDeclaredFields()) {
            if (field.getType() == type) {
                return setAccessible(field);
            }
        }
        return null;
    }

    public static Method findMethod(final Class<?> clazz, final Class<?> returnType, Class... params) {
        return findMethod(clazz, 0, returnType, params);
    }

    public static Method findMethod(final Class<?> clazz, int index, int hasMods, int noMods, final Class<?> returnType, Class... params) {
        outer:
        for (Method method : sortMethods(clazz.getDeclaredMethods())) {
            if (returnType == null || method.getReturnType() == returnType) {
                Class<?>[] mp = method.getParameterTypes();
                int mods = method.getModifiers();
                if ((mods & hasMods) != hasMods || (mods & noMods) != 0) continue;
                if (params == null) {
                    if (index-- == 0) return setAccessible(method);
                    else {
                        continue;
                    }
                }
                if (mp.length == params.length) {
                    for (int i = 0; i < mp.length; i++) {
                        if (mp[i] != params[i]) continue outer;
                    }
                    if (index-- == 0) return setAccessible(method);
                    else {
                        continue;
                    }
                }
            }
        }
        return null;
    }

    public static Method[] sortMethods(Method[] methods) {
        Arrays.sort(methods, (o1, o2) -> o1.getName().compareTo(o2.getName()));
        return methods;
    }

    public static Field[] sortFields(Field[] fields) {
        Arrays.sort(fields, (o1, o2) -> o1.getName().compareTo(o2.getName()));
        return fields;
    }

    public static Method findMethod(final Class<?> clazz, int index, final Class<?> returnType, Class... params) {
        return findMethod(clazz, index, 0, 0, returnType, params);
    }

    public static <T extends AccessibleObject> T setAccessible(final T ao) {
        ao.setAccessible(true);
        return ao;
    }

    @SuppressWarnings("unchecked")
    public static <T> T getField(final Field field, final Object instance) {
        if (field == null) {
            throw new RuntimeException("No such field");
        }
        field.setAccessible(true);
        try {
            return (T) field.get(instance);
        } catch (final Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public static void setField(String fieldName, Object instance, Object value) {
        try {
            Field field = instance.getClass().getDeclaredField(fieldName);
            setField(field, instance, value);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    public static void setField(final Field field, final Object instance, final Object value) {
        if (field == null) {
            throw new RuntimeException("No such field");
        }
        field.setAccessible(true);
        try {
            field.set(instance, value);
        } catch (final Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public static Class<?> getClass(final String name) {
        try {
            return Class.forName(name);
        } catch (final ClassNotFoundException ex) {
            return null;
        }
    }

    public static <T> Class<? extends T> getClass(final String name, final Class<T> superClass) {
        try {
            return Class.forName(name).asSubclass(superClass);
        } catch (ClassCastException | ClassNotFoundException ex) {
            return null;
        }
    }


    /**
     * get RefClass object by real class
     *
     * @param clazz class
     * @return RefClass based on passed class
     */
    public static RefClass getRefClass(final Class clazz) {
        return new RefClass(clazz);
    }

    /**
     * RefClass - utility to simplify work with reflections.
     */
    public static class RefClass {
        private final Class<?> clazz;

        private RefClass(final Class<?> clazz) {
            this.clazz = clazz;
        }

        /**
         * get passed class
         *
         * @return class
         */
        public Class<?> getRealClass() {
            return this.clazz;
        }

        /**
         * see {@link Class#isInstance(Object)}
         *
         * @param object the object to check
         * @return true if object is an instance of this class
         */
        public boolean isInstance(final Object object) {
            return this.clazz.isInstance(object);
        }

        /**
         * get existing method by name and types
         *
         * @param name  name
         * @param types method parameters. can be Class or RefClass
         * @return RefMethod object
         * @throws RuntimeException if method not found
         */
        public RefMethod getMethod(final String name, final Object... types) throws NoSuchMethodException {
            try {
                final Class[] classes = new Class[types.length];
                int i = 0;
                for (final Object e : types) {
                    if (e instanceof Class) {
                        classes[i++] = (Class) e;
                    } else if (e instanceof RefClass) {
                        classes[i++] = ((RefClass) e).getRealClass();
                    } else {
                        classes[i++] = e.getClass();
                    }
                }
                try {
                    return new RefMethod(this.clazz.getMethod(name, classes));
                } catch (final NoSuchMethodException ignored) {
                    return new RefMethod(this.clazz.getDeclaredMethod(name, classes));
                }
            } catch (final Exception e) {
                throw new RuntimeException(e);
            }
        }

        /**
         * get existing constructor by types
         *
         * @param types parameters. can be Class or RefClass
         * @return RefMethod object
         * @throws RuntimeException if constructor not found
         */
        public RefConstructor getConstructor(final Object... types) {
            try {
                final Class[] classes = new Class[types.length];
                int i = 0;
                for (final Object e : types) {
                    if (e instanceof Class) {
                        classes[i++] = (Class) e;
                    } else if (e instanceof RefClass) {
                        classes[i++] = ((RefClass) e).getRealClass();
                    } else {
                        classes[i++] = e.getClass();
                    }
                }
                try {
                    return new RefConstructor(this.clazz.getConstructor(classes));
                } catch (final NoSuchMethodException ignored) {
                    return new RefConstructor(this.clazz.getDeclaredConstructor(classes));
                }
            } catch (final Exception e) {
                throw new RuntimeException(e);
            }
        }

        /**
         * find method by type parameters
         *
         * @param types parameters. can be Class or RefClass
         * @return RefMethod object
         * @throws RuntimeException if method not found
         */
        public RefMethod findMethod(final Object... types) {
            final Class[] classes = new Class[types.length];
            int t = 0;
            for (final Object e : types) {
                if (e instanceof Class) {
                    classes[t++] = (Class) e;
                } else if (e instanceof RefClass) {
                    classes[t++] = ((RefClass) e).getRealClass();
                } else {
                    classes[t++] = e.getClass();
                }
            }
            final List<Method> methods = new ArrayList<>();
            Collections.addAll(methods, this.clazz.getMethods());
            Collections.addAll(methods, this.clazz.getDeclaredMethods());
            findMethod:
            for (final Method m : methods) {
                final Class<?>[] methodTypes = m.getParameterTypes();
                if (methodTypes.length != classes.length) {
                    continue;
                }
                for (final Class aClass : classes) {
                    if (!Arrays.equals(classes, methodTypes)) {
                        continue findMethod;
                    }
                    return new RefMethod(m);
                }
            }
            throw new RuntimeException("no such method");
        }

        /**
         * find method by name
         *
         * @param names possible names of method
         * @return RefMethod object
         * @throws RuntimeException if method not found
         */
        public RefMethod findMethodByName(final String... names) {
            final List<Method> methods = new ArrayList<>();
            Collections.addAll(methods, this.clazz.getMethods());
            Collections.addAll(methods, this.clazz.getDeclaredMethods());
            for (final Method m : methods) {
                for (final String name : names) {
                    if (m.getName().equals(name)) {
                        return new RefMethod(m);
                    }
                }
            }
            throw new RuntimeException("no such method");
        }

        /**
         * find method by return value
         *
         * @param type type of returned value
         * @return RefMethod
         * @throws RuntimeException if method not found
         */
        public RefMethod findMethodByReturnType(final RefClass type) {
            return this.findMethodByReturnType(type.clazz);
        }

        /**
         * find method by return value
         *
         * @param type type of returned value
         * @return RefMethod
         * @throws RuntimeException if method not found
         */
        public RefMethod findMethodByReturnType(Class type) {
            if (type == null) {
                type = void.class;
            }
            final List<Method> methods = new ArrayList<>();
            Collections.addAll(methods, this.clazz.getMethods());
            Collections.addAll(methods, this.clazz.getDeclaredMethods());
            for (final Method m : methods) {
                if (type.equals(m.getReturnType())) {
                    return new RefMethod(m);
                }
            }
            throw new RuntimeException("no such method");
        }

        /**
         * find constructor by number of arguments
         *
         * @param number number of arguments
         * @return RefConstructor
         * @throws RuntimeException if constructor not found
         */
        public RefConstructor findConstructor(final int number) {
            final List<Constructor> constructors = new ArrayList<>();
            Collections.addAll(constructors, this.clazz.getConstructors());
            Collections.addAll(constructors, this.clazz.getDeclaredConstructors());
            for (final Constructor m : constructors) {
                if (m.getParameterTypes().length == number) {
                    return new RefConstructor(m);
                }
            }
            throw new RuntimeException("no such constructor");
        }

        /**
         * get field by name
         *
         * @param name field name
         * @return RefField
         * @throws RuntimeException if field not found
         */
        public RefField getField(final String name) {
            try {
                try {
                    return new RefField(this.clazz.getField(name));
                } catch (final NoSuchFieldException ignored) {
                    return new RefField(this.clazz.getDeclaredField(name));
                }
            } catch (final Exception e) {
                throw new RuntimeException(e);
            }
        }

        /**
         * find field by type
         *
         * @param type field type
         * @return RefField
         * @throws RuntimeException if field not found
         */
        public RefField findField(final RefClass type) {
            return this.findField(type.clazz);
        }

        /**
         * find field by type
         *
         * @param type field type
         * @return RefField
         * @throws RuntimeException if field not found
         */
        public RefField findField(Class type) {
            if (type == null) {
                type = void.class;
            }
            final List<Field> fields = new ArrayList<>();
            Collections.addAll(fields, this.clazz.getFields());
            Collections.addAll(fields, this.clazz.getDeclaredFields());
            for (final Field f : fields) {
                if (type.equals(f.getType())) {
                    return new RefField(f);
                }
            }
            throw new RuntimeException("no such field");
        }
    }

    /**
     * Method wrapper
     */
    public static class RefMethod {
        private final Method method;

        private RefMethod(final Method method) {
            this.method = method;
            method.setAccessible(true);
        }

        /**
         * @return passed method
         */
        public Method getRealMethod() {
            return this.method;
        }

        /**
         * @return owner class of method
         */
        public RefClass getRefClass() {
            return new RefClass(this.method.getDeclaringClass());
        }

        /**
         * @return class of method return type
         */
        public RefClass getReturnRefClass() {
            return new RefClass(this.method.getReturnType());
        }

        /**
         * apply method to object
         *
         * @param e object to which the method is applied
         * @return RefExecutor with method call(...)
         */
        public RefExecutor of(final Object e) {
            return new RefExecutor(e);
        }

        /**
         * call static method
         *
         * @param params sent parameters
         * @return return value
         */
        public Object call(final Object... params) {
            try {
                return this.method.invoke(null, params);
            } catch (final Exception e) {
                throw new RuntimeException(e);
            }
        }

        public class RefExecutor {
            final Object e;

            public RefExecutor(final Object e) {
                this.e = e;
            }

            /**
             * apply method for selected object
             *
             * @param params sent parameters
             * @return return value
             * @throws RuntimeException if something went wrong
             */
            public Object call(final Object... params) {
                try {
                    return RefMethod.this.method.invoke(this.e, params);
                } catch (final Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    /**
     * Constructor wrapper
     */
    public static class RefConstructor {
        private final Constructor constructor;

        private RefConstructor(final Constructor constructor) {
            this.constructor = constructor;
            constructor.setAccessible(true);
        }

        /**
         * @return passed constructor
         */
        public Constructor getRealConstructor() {
            return this.constructor;
        }

        /**
         * @return owner class of method
         */
        public RefClass getRefClass() {
            return new RefClass(this.constructor.getDeclaringClass());
        }

        /**
         * create new instance with constructor
         *
         * @param params parameters for constructor
         * @return new object
         * @throws RuntimeException if something went wrong
         */
        public Object create(final Object... params) {
            try {
                return this.constructor.newInstance(params);
            } catch (final Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static class RefField {
        private final Field field;

        private RefField(final Field field) {
            this.field = field;
            field.setAccessible(true);
        }

        /**
         * @return passed field
         */
        public Field getRealField() {
            return this.field;
        }

        /**
         * @return owner class of field
         */
        public RefClass getRefClass() {
            return new RefClass(this.field.getDeclaringClass());
        }

        /**
         * @return type of field
         */
        public RefClass getFieldRefClass() {
            return new RefClass(this.field.getType());
        }

        /**
         * apply fiend for object
         *
         * @param e applied object
         * @return RefExecutor with getter and setter
         */
        public RefExecutor of(final Object e) {
            return new RefExecutor(e);
        }

        public class RefExecutor {
            final Object e;

            public RefExecutor(final Object e) {
                this.e = e;
            }

            /**
             * set field value for applied object
             *
             * @param param value
             */
            public void set(final Object param) {
                try {
                    RefField.this.field.set(this.e, param);
                } catch (final Exception e) {
                    throw new RuntimeException(e);
                }
            }

            /**
             * get field value for applied object
             *
             * @return value of field
             */
            public Object get() {
                try {
                    return RefField.this.field.get(this.e);
                } catch (final Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }
}
