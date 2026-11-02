package wqs.read;

/** Level-0 read filter. {@code productId == null} means all products. */
public record Level0Filter(long userId, Integer productId, boolean excludeCancelled) {

    /** The counter tables store the all-products total under product key 0. */
    public int counterProductKey() {
        return productId == null ? 0 : productId;
    }
}
