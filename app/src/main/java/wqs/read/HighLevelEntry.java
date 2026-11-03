package wqs.read;

/**
 * One top-level entry of a level-1/2 page (spec 4.1, 7.4): a {@link SessionView} or an
 * unsessioned transaction as a plain {@link TxView}, interleaved in page-index order.
 */
public sealed interface HighLevelEntry permits SessionView, TxView {}
