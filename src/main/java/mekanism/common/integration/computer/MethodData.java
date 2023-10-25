package mekanism.common.integration.computer;

import mekanism.common.integration.computer.ComputerMethodFactory.ComputerFunctionCaller;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;

public record MethodData<T>(String name, MethodRestriction restriction, String[] requiredMods, boolean threadSafe, String[] argumentNames, Class<?>[] argClasses,
                            Class<?> returnType, Class<?>[] returnExtra, ComputerFunctionCaller<T> handler, @Nullable String methodDescription,
                            boolean requiresPublicSecurity) {
    public MethodData {
        if (argClasses.length != argumentNames.length) {
            throw new IllegalStateException("Argument arrays should be the same length");
        }
    }

    public boolean supports(@Nullable T subject) {
        return restriction.test(subject) && modsLoaded(requiredMods);
    }

    private boolean modsLoaded(String[] mods) {
        for (String mod : mods) {
            if (!ModList.get().isLoaded(mod)) {
                return false;
            }
        }
        return true;
    }

    public static <T> Builder<T> builder(String methodName, ComputerFunctionCaller<T> handler) {
        return new Builder<>(methodName, handler);
    }

    static String[] NO_STRINGS = new String[0];
    static Class<?>[] NO_CLASSES = new Class[0];

    public static class Builder<T> {

        private final String methodName;
        private MethodRestriction restriction = MethodRestriction.NONE;
        private String[] requiredMods = NO_STRINGS;
        private boolean threadSafe = false;
        private String[] argumentNames = NO_STRINGS;
        private Class<?>[] argClasses = NO_CLASSES;
        private Class<?> returnType = void.class;
        private Class<?>[] returnExtra = NO_CLASSES;
        private final ComputerFunctionCaller<T> handler;
        private @Nullable String methodDescription = null;
        private boolean requiresPublicSecurity = false;

        private Builder(String methodName, ComputerFunctionCaller<T> handler) {
            this.methodName = methodName;
            this.handler = handler;
        }

        public MethodData<T> build() {
            return new MethodData<>(methodName, restriction, requiredMods, threadSafe, argumentNames,argClasses, returnType, returnExtra, handler, methodDescription, requiresPublicSecurity);
        }

        public Builder<T> restriction(MethodRestriction restriction) {
            this.restriction = restriction;
            return this;
        }

        public Builder<T> requiredMods(String... requiredMods) {
            this.requiredMods = requiredMods;
            return this;
        }

        public Builder<T> threadSafe() {
            this.threadSafe = true;
            return this;
        }

        public Builder<T> arguments(String[] argumentNames, Class<?>[] argClasses) {
            if (argClasses.length != argumentNames.length) {
                throw new IllegalStateException("Argument arrays should be the same length");
            }
            this.argumentNames = argumentNames;
            this.argClasses = argClasses;
            return this;
        }

        public Builder<T> returnType(Class<?> returnType) {
            this.returnType = returnType;
            return this;
        }

        public Builder<T> returnExtra(Class<?>... returnExtra) {
            this.returnExtra = returnExtra;
            return this;
        }

        public Builder<T> methodDescription(String methodDescription) {
            this.methodDescription = methodDescription;
            return this;
        }

        public Builder<T> requiresPublicSecurity() {
            this.requiresPublicSecurity = true;
            return this;
        }
    }
}