package in.smashandclash.minecraft.api;

/** An API failure: the HTTP status and the problem+json fields (as the SDK's SmashAndClashError). */
public class SmashAndClashError extends RuntimeException {
	public final int status;
	public final String code;
	public final String hint;

	public SmashAndClashError(int status, String code, String message, String hint) {
		super(message);
		this.status = status;
		this.code = code;
		this.hint = hint;
	}
}
