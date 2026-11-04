package p;
public class Tgt {
  static final Object SENTINEL = new Object();
  static Object id(Object x) { return x; }
  // The branch target statement itself reads the tainted value:
  //   if_acmpne L; ...; L: aload r; checkcast String; astore a
  // -- kotlinc's fast path `if (r != COROUTINE_SUSPENDED) goto L; L: a = (String) r`
  static Object targetUsesTainted() {
    Object r = id(System.getenv("SECRET"));
    if (r == SENTINEL) return SENTINEL;
    String a = (String) r;
    System.out.println(a);
    return a;
  }
  // Control: an unrelated statement at the branch target, then the use.
  static Object targetUnrelated() {
    Object r = id(System.getenv("SECRET"));
    if (r == SENTINEL) return SENTINEL;
    int pad = 0;
    String a = (String) r;
    System.out.println(a);
    return a;
  }
}
