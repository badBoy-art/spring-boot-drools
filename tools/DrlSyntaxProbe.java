import com.example.drools.service.DrlSyntax;

/** 直接验 DrlSyntax 的算子渲染（用 target/classes 里真实编译产物，绕开"页面/库里脏状态"的干扰） */
public class DrlSyntaxProbe {
    public static void main(String[] args) {
        String[][] cases = {
            {"startsWith", "${v}", "STRING"},
            {"endsWith", "${v}", "STRING"},
            {"以…开头", "${v}", "STRING"},
            {"not endsWith", "abc", "STRING"},
            {"in", "${v}", "CSV"},
            {">=", "${v}", "DECIMAL"},
            {"contains", "${v}", "STRING"},
            {"不为空", "", "STRING"},
            {"matches", "[0-9]+", "STRING"},
        };
        for (String[] c : cases) {
            String out = DrlSyntax.render("F", c[0], c[1], c[2]);
            System.out.println(String.format("%-12s -> %s", c[0], out));
        }
        System.out.println("ops 总数 = " + DrlSyntax.operators().size() + "，types 总数 = " + DrlSyntax.types().size());
    }
}
