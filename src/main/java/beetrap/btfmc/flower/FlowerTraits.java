package beetrap.btfmc.flower;

/**
 * Turns a flower's data panel numbers (see FlowerValueScoreboardDisplayerService) into the plain
 * words Bip uses with kids. Smell, nectar and water share one scale: under ~3.3 is low, 3.3-4.7 is
 * medium, above 4.7 is high. Sunlight is a near-constant tiny value, so it has no level.
 */
public final class FlowerTraits {

    public enum Trait { SMELL, NECTAR, WATER }

    private static final String[] SMELL_WORDS = {"faint", "medium", "strong"};
    private static final String[] NECTAR_WORDS = {"just a little sweet", "kinda sweet",
            "super sweet"};
    private static final String[] WATER_WORDS = {"a little", "some", "a lot of"};
    private static final String[] LEVEL_WORDS = {"low", "medium", "high"};

    private FlowerTraits() {
        throw new AssertionError();
    }

    /** 0 = low, 1 = medium, 2 = high. */
    public static int level(double value) {
        if(value < 3.333) {
            return 0;
        }
        return value < 4.667 ? 1 : 2;
    }

    public static double value(Flower f, Trait trait) {
        return switch(trait) {
            case SMELL -> f.w;
            case NECTAR -> f.x;
            case WATER -> f.y;
        };
    }

    public static String label(Trait trait) {
        return switch(trait) {
            case SMELL -> "smell";
            case NECTAR -> "nectar";
            case WATER -> "water need";
        };
    }

    /** Kid-friendly word for a trait's level, e.g. "super sweet" for high nectar. */
    public static String word(Flower f, Trait trait) {
        int level = level(value(f, trait));
        return switch(trait) {
            case SMELL -> SMELL_WORDS[level];
            case NECTAR -> NECTAR_WORDS[level];
            case WATER -> WATER_WORDS[level];
        };
    }

    public static String levelWord(double value) {
        return LEVEL_WORDS[level(value)];
    }

    /** One line describing every data panel row of a flower, for Bip's context. */
    public static String describe(Flower f, String colorName) {
        return String.format("Flower number %d | Color: %.2f (%s) | Smell strength: %.2f (%s) | "
                        + "Nectar sweetness: %.2f (%s) | Water needed: %.2f (%s) | "
                        + "Sunlight needed: %.2f%s",
                f.getNumber(), f.v, colorName, f.w, levelWord(f.w), f.x, levelWord(f.x),
                f.y, levelWord(f.y), f.z, f.hasWithered() ? " | WITHERED" : "");
    }
}
