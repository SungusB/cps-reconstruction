package p;
public class Ctor {
  static class Box { final String v; Box(String v) { this.v = v; } }
  // javac: new Box; dup; invokestatic getenv; invokespecial <init>
  static void sourceInsideNew() {
    Box b = new Box(System.getenv("SECRET"));
    System.out.println(b);
  }
  // javac: invokestatic getenv; astore s; new Box; dup; aload s; invokespecial
  static void sourceBeforeNew() {
    String s = System.getenv("SECRET");
    Box b = new Box(s);
    System.out.println(b);
  }
}
