import com.sun.source.util.JavacTask;
import javax.tools.*;
import java.util.*;

/** Syntax-only check. Does not replace compiling against Android SDK or running on a device. */
public class SourceSyntaxCheck {
    public static void main(String[] paths)throws Exception {
        JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics=new DiagnosticCollector<>();
        try(StandardJavaFileManager manager=compiler.getStandardFileManager(diagnostics,null,null)) {
            JavacTask task=(JavacTask)compiler.getTask(null,manager,diagnostics,
                Arrays.asList("-proc:none"),null,manager.getJavaFileObjects(paths));
            task.parse();
            for(Diagnostic<?> d:diagnostics.getDiagnostics())if(d.getKind()==Diagnostic.Kind.ERROR)throw new AssertionError(d.toString());
            System.out.println("Syntax OK: "+paths.length+" Java files. Android types were NOT checked.");
        }
    }
}
