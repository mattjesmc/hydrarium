package com.mattjesmc.hydrarium;

import java.util.Locale;

/**
 * The second axis of a water, and the one that is <b>not</b> free.
 *
 * <p>A water is {@code (tint, effect)}. A tint is a number, so tints are a table and a new one is a
 * row. An <b>effect is code</b> — so the set is small, it is enumerated here in Java, and a
 * catalogue may only <em>choose</em> from it and parameterise it. That boundary is deliberate: if
 * effects were declarable, tier 2's promise ("a consumer writes no Java") would quietly become a
 * lie the first time somebody wanted a behaviour that did not exist yet, and the lie would be
 * discovered by a modpack rather than by a compiler.
 *
 * <p>The starting set comes from the brief and from rocketeer's existing biome families
 * ({@code ash}, {@code bioluminescent}, {@code molten}, {@code frozen}, {@code pulse}).
 *
 * <p><b>An unknown name is {@link #NONE}, not a parse failure.</b> A catalogue naming an effect
 * that this build does not have should cost that water its behaviour and keep its colour, which is
 * the degradation a player can see and report; rejecting the catalogue would cost a consumer every
 * water in it over one string.
 */
public enum Effect {

    /** A colour and nothing else. The overwhelming majority, including all sixteen dyes. */
    NONE,

    /**
     * Bioluminescent.
     *
     * <p><b>v1 is a render effect only</b>: the tint is drawn fullbright and the water carries
     * particles, but it emits no light. Real light from a non-blockstate positional field means
     * driving the light engine per position, which is both expensive and invasive, and it is not
     * worth that to make a pond legible at night. Revisit with a measurement, not with an intuition.
     */
    GLOW,

    /** Ash: particles, reduced visibility, slowed swim. */
    ASH,

    /** Irradiated: a status effect on contact, and particles. */
    DECAY;

    /** The name a catalogue writes. Lower case, which is the convention every other data file uses. */
    public String catalogueName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Resolve a catalogue's string. Unknown names — and the absent field, which arrives here as
     * {@code "none"} from the codec's default — give {@link #NONE}.
     */
    public static Effect byName(final String name) {
        for (final Effect effect : values()) {
            if (effect.catalogueName().equals(name)) {
                return effect;
            }
        }
        return NONE;
    }

    /** Whether {@link #byName} would have recognised this string. Used to log the difference. */
    public static boolean known(final String name) {
        for (final Effect effect : values()) {
            if (effect.catalogueName().equals(name)) {
                return true;
            }
        }
        return false;
    }
}
