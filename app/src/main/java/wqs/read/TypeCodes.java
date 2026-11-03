package wqs.read;

/** Resolves a stored "wqTxType" id to its wire code, for the item mappers. */
@FunctionalInterface
public interface TypeCodes {

    String codeOf(short typeId);
}
