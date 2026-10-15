package trafilatura.compat;

/** Two-element Python tuple returned by a function. */
public record Pair<A, B>(A first, B second) {
    public static <A, B> Pair<A, B> of(A first, B second) {
        return new Pair<>(first, second);
    }
}
