package wqs.stream;

/** A stream message as received, before decoding: its offset and raw body. */
record ReceivedMessage(long offset, byte[] body) {}
