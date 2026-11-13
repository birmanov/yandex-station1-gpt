import java.io.IOException;
import java.util.*;

/**
 * Just enough JSON for OpenAI responses: a recursive-descent parser into Map/List/String/Double/
 * Boolean/null, a path lookup, and a quoter. Nesting is capped at 64 levels.
 */
public final class MiniJson {
  static final int MAX_DEPTH = 64;

  private final String s;
  private int at;

  private MiniJson(String s) {
    this.s = s;
  }

  public static Object parse(String json) throws IOException {
    MiniJson p = new MiniJson(json);
    Object value = p.value(0);
    p.skipSpace();
    if (p.at != p.s.length()) throw p.error("Trailing characters");
    return value;
  }

  /** Walks keys (for maps) and numeric indices (for lists); null unless the leaf is a String. */
  public static String string(Object root, String... path) {
    Object cur = root;
    for (String step : path) {
      if (cur instanceof Map) {
        cur = ((Map<?, ?>) cur).get(step);
      } else if (cur instanceof List) {
        List<?> list = (List<?>) cur;
        int index;
        try {
          index = Integer.parseInt(step);
        } catch (NumberFormatException e) {
          return null;
        }
        if (index < 0 || index >= list.size()) return null;
        cur = list.get(index);
      } else {
        return null;
      }
    }
    return cur instanceof String ? (String) cur : null;
  }

  public static String quote(String text) {
    if (text == null) return "\"\"";
    StringBuilder b = new StringBuilder(text.length() + 2).append('"');
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '"': b.append("\\\""); break;
        case '\\': b.append("\\\\"); break;
        case '\n': b.append("\\n"); break;
        case '\r': b.append("\\r"); break;
        case '\t': b.append("\\t"); break;
        default:
          if (c < 32 || c == ' ' || c == ' ') b.append(String.format(Locale.US, "\\u%04x", (int) c));
          else b.append(c);
      }
    }
    return b.append('"').toString();
  }

  private Object value(int depth) throws IOException {
    if (depth > MAX_DEPTH) throw error("Nesting too deep");
    skipSpace();
    if (at >= s.length()) throw error("Unexpected end");
    char c = s.charAt(at);
    switch (c) {
      case '{': return object(depth);
      case '[': return array(depth);
      case '"': return string();
      case 't': literal("true"); return Boolean.TRUE;
      case 'f': literal("false"); return Boolean.FALSE;
      case 'n': literal("null"); return null;
      default:
        if (c == '-' || (c >= '0' && c <= '9')) return number();
        throw error("Unexpected character");
    }
  }

  private Map<String, Object> object(int depth) throws IOException {
    Map<String, Object> map = new LinkedHashMap<String, Object>();
    at++;
    skipSpace();
    if (peek() == '}') { at++; return map; }
    while (true) {
      skipSpace();
      if (peek() != '"') throw error("Expected string key");
      String key = string();
      skipSpace();
      if (peek() != ':') throw error("Expected ':'");
      at++;
      map.put(key, value(depth + 1));
      skipSpace();
      char c = peek();
      at++;
      if (c == '}') return map;
      if (c != ',') throw error("Expected ',' or '}'");
    }
  }

  private List<Object> array(int depth) throws IOException {
    List<Object> list = new ArrayList<Object>();
    at++;
    skipSpace();
    if (peek() == ']') { at++; return list; }
    while (true) {
      list.add(value(depth + 1));
      skipSpace();
      char c = peek();
      at++;
      if (c == ']') return list;
      if (c != ',') throw error("Expected ',' or ']'");
    }
  }

  private String string() throws IOException {
    at++;
    StringBuilder b = new StringBuilder();
    while (true) {
      if (at >= s.length()) throw error("Unterminated string");
      char c = s.charAt(at++);
      if (c == '"') return b.toString();
      if (c != '\\') { b.append(c); continue; }
      if (at >= s.length()) throw error("Unterminated escape");
      char e = s.charAt(at++);
      switch (e) {
        case '"': case '\\': case '/': b.append(e); break;
        case 'b': b.append('\b'); break;
        case 'f': b.append('\f'); break;
        case 'n': b.append('\n'); break;
        case 'r': b.append('\r'); break;
        case 't': b.append('\t'); break;
        case 'u':
          if (at + 4 > s.length()) throw error("Bad unicode escape");
          try {
            b.append((char) Integer.parseInt(s.substring(at, at + 4), 16));
          } catch (NumberFormatException x) {
            throw error("Bad unicode escape");
          }
          at += 4;
          break;
        default: throw error("Bad escape");
      }
    }
  }

  private Double number() throws IOException {
    int start = at;
    if (peek() == '-') at++;
    while (at < s.length() && "0123456789.eE+-".indexOf(s.charAt(at)) >= 0) at++;
    try {
      return Double.valueOf(s.substring(start, at));
    } catch (NumberFormatException e) {
      throw error("Bad number");
    }
  }

  private void literal(String word) throws IOException {
    if (!s.startsWith(word, at)) throw error("Bad literal");
    at += word.length();
  }

  private char peek() throws IOException {
    if (at >= s.length()) throw error("Unexpected end");
    return s.charAt(at);
  }

  private void skipSpace() {
    while (at < s.length() && " \t\r\n".indexOf(s.charAt(at)) >= 0) at++;
  }

  private IOException error(String message) {
    return new IOException("JSON: " + message + " at " + at);
  }
}
