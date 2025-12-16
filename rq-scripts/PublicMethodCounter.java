import sootup.core.model.SootMethod;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;
import sootup.java.core.views.JavaView;

public class PublicMethodCounter {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java PublicMethodCounter <path-to-jar>");
            System.exit(1);
        }

        String pathToJar = args[0];

        JavaView view = createJavaView(pathToJar);
        long publicMethodCount = countPublicMethods(view);

        System.out.println(publicMethodCount);
    }

    private static JavaView createJavaView(String pathToJar) {
        JavaClassPathAnalysisInputLocation inputLocation =
                new JavaClassPathAnalysisInputLocation(pathToJar);
        return new JavaView(inputLocation);
    }

    private static long countPublicMethods(JavaView view) {
        return view.getClasses()
                .flatMap(c -> c.getMethods().stream())
                .filter(SootMethod::isPublic)
                .count();
    }
}
