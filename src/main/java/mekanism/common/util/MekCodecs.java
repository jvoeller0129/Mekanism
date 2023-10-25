package mekanism.common.util;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import java.util.Arrays;
import java.util.Collections;
import mekanism.common.integration.computer.MethodRestriction;
import net.minecraft.util.ExtraCodecs;

public class MekCodecs {

    public static final Codec<MethodRestriction> METHOD_RESTRICTION_CODEC = ExtraCodecs.stringResolverCodec(MethodRestriction::name, MethodRestriction::valueOf);
    public static final Codec<Class<?>> CLASS_TO_STRING_CODEC = ExtraCodecs.stringResolverCodec(Class::getName, s->{
        try {
            return Class.forName(s);
        } catch (ClassNotFoundException e) {
            return null;
        }
    });

    public static MapCodec<Class<?>[]> optionalClassArrayCodec(String fieldName) {
        return CLASS_TO_STRING_CODEC.listOf().optionalFieldOf(fieldName, Collections.emptyList()).xmap(cl -> cl.toArray(new Class[0]), Arrays::asList);
    }
}
