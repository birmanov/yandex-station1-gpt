import java.io.IOException;
import java.util.List;
import java.util.Map;

public final class MiniJsonCheck {
  static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }

  public static void main(String[] args) throws Exception {
    Object root = MiniJson.parse("{\"choices\":[{\"message\":{\"content\":\"Привет \\u2014 мир\\n\"}, \"finish_reason\":\"stop\"}], \"n\": 1.5, \"neg\": -2e3, \"ok\": true, \"none\": null, \"list\": []}");
    require("Привет — мир\n".equals(MiniJson.string(root, "choices", "0", "message", "content")), "nested path");
    require(MiniJson.string(root, "choices", "1", "message") == null, "missing index");
    require(MiniJson.string(root, "nope") == null, "missing key");
    require(MiniJson.string(root, "n") == null, "non-string is null");
    require(MiniJson.string(root, "choices", "x") == null, "non-numeric index on list");
    Map<?, ?> map = (Map<?, ?>) root;
    require(map.get("n").equals(1.5) && map.get("neg").equals(-2000.0) && map.get("ok").equals(Boolean.TRUE), "numbers and booleans");
    require(map.containsKey("none") && map.get("none") == null && ((List<?>) map.get("list")).isEmpty(), "null and empty list");
    require("stop".equals(MiniJson.string(root, "choices", "0", "finish_reason")), "sibling key");

    require(MiniJson.quote("a\"b\\c\nd").equals("\"a\\\"b\\\\c\\nd\""), "quote escapes");
    require(MiniJson.quote("tab\t\r\u0001 ").equals("\"tab\\t\\r\\u0001\\u2028\""), "control and line separators");
    require(MiniJson.quote(null).equals("\"\""), "null quotes to empty");
    require(MiniJson.parse(MiniJson.quote("round \"trip\" \\ \n ✓")).equals("round \"trip\" \\ \n ✓"), "quote/parse round trip");

    bad("{\"a\":", "truncated");
    bad("[1,2] x", "trailing garbage");
    bad("{a:1}", "unquoted key");
    bad("\"unterminated", "unterminated string");
    bad("[1,]", "trailing comma");
    StringBuilder deep = new StringBuilder();
    for (int i = 0; i < 70; i++) deep.append('[');
    for (int i = 0; i < 70; i++) deep.append(']');
    bad(deep.toString(), "nesting deeper than 64");
    System.out.println("PASS MiniJson parse, path lookup, quote, malformed input rejected");
  }

  static void bad(String json, String what) {
    try {
      MiniJson.parse(json);
      require(false, what + " must be rejected");
    } catch (IOException expected) { }
  }
}
