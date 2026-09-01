package com.mattjesmc.hydrarium;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;

/**
 * What being in a water does to whoever is in it.
 *
 * <h2>Why this is an entity tick and not a fluid</h2>
 *
 * The obvious implementation is {@code WaterFluid#entityInside}, and it is the wrong one twice
 * over: it needs a fluid subclass, which is the exact thing this mod exists not to register, and it
 * puts hydrarium in a method every other mod that touches water also wants. Asking each entity
 * whether it happens to be standing in a tinted position is the same question from the other end,
 * costs one field read for the overwhelming majority of entities (they are not in water at all),
 * and collides with nothing.
 *
 * <p>It is deliberately <b>not</b> a level sweep either. Iterating every entity in a level every
 * few ticks to find the handful in water is more work than the mixin it was avoiding, and it scales
 * with the wrong number.
 *
 * <h2>Effects are throttled, and the throttle is the design</h2>
 *
 * {@link #INTERVAL} is not a performance knob. A status effect re-applied every tick can never
 * expire, so its duration stops meaning anything and leaving the water stops mattering; applying it
 * on an interval slightly shorter than the duration is what makes "you are affected while you are
 * in it, and for a moment after" true. Change one of the two numbers without the other and the
 * effect either flickers or becomes permanent.
 */
public final class WaterEffects {

    /** Ticks between applications. See the class doc: this is paired with {@link #DURATION}. */
    public static final int INTERVAL = 10;

    /** Ticks an application lasts. Longer than {@link #INTERVAL}, so contact reads as continuous. */
    public static final int DURATION = 40;

    private WaterEffects() {
    }

    /**
     * Nothing to install: the hook is {@code LivingEntityMixin} and it is compiled in.
     *
     * <p>The method exists so that {@link Hydrarium#onInitialize} reads as a list of the seams this
     * mod occupies rather than a list of the ones that happened to need a call. If effects ever
     * grow a registry, this is where it goes and no caller changes.
     */
    public static void install() {
    }

    /**
     * Called from every living entity's tick. Must be cheap for the case where nothing applies,
     * which is nearly every call.
     */
    public static void tick(final LivingEntity entity) {
        if (!entity.isInWater() || entity.tickCount % INTERVAL != 0) {
            return;
        }
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }
        // blockPosition() is the feet. That is the right sample for "wading in it" as well as for
        // "swimming in it", and it is the position isInWater() was answered from.
        final BlockPos pos = entity.blockPosition();
        final WaterType water = TintField.get(level, pos);
        if (water == null || water.effect() == Effect.NONE) {
            return;
        }

        switch (water.effect()) {
            case ASH -> {
                // Slowness rather than a swim-speed attribute: the attribute would have to be added
                // and removed, and an entity that dies or unloads mid-water would keep it. An
                // effect expires on its own, which is the property that makes this safe to apply
                // from a tick with no matching "stop" path.
                entity.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, DURATION, 0, true, false));
                level.sendParticles(ParticleTypes.ASH, entity.getX(), entity.getEyeY(), entity.getZ(),
                        4, 0.3, 0.3, 0.3, 0.0);
            }
            case DECAY -> {
                entity.addEffect(new MobEffectInstance(MobEffects.POISON, DURATION, 0, true, false));
                level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, entity.getX(), entity.getEyeY(), entity.getZ(),
                        3, 0.3, 0.3, 0.3, 0.0);
            }
            // GLOW is a render effect only in v1 and deliberately does nothing to an entity: real
            // light from a non-blockstate positional field means driving the light engine per
            // position, which is expensive and invasive and not worth it to make a pond legible at
            // night. Revisit with a measurement, not with an intuition.
            case GLOW, NONE -> {
            }
        }
    }
}
