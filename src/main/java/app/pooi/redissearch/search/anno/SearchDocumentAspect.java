package app.pooi.redissearch.search.anno;

import app.pooi.redissearch.search.FieldMeta;
import app.pooi.redissearch.search.SearchCore;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.LocalVariableTableParameterNameDiscoverer;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

@Aspect
@Component
public class SearchDocumentAspect {

    @Autowired
    private SearchCore searchCore;

    @Pointcut("@annotation(CreateIndex)")
    void saveDocumentAnnotationPointCut() {
    }

    @Pointcut("@annotation(UpdateDocument)")
    void updateDocumentAnnotationPointCut() {
    }

    @Pointcut("@annotation(DeleteDocument)")
    void deleteDocumentAnnotationPointCut() {
    }

    @Around("saveDocumentAnnotationPointCut()")
    public Object saveDocument(ProceedingJoinPoint joinPoint) throws Throwable {
        Object retVal = joinPoint.proceed();

        final MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
        final CreateIndex annotation = methodSignature.getMethod().getAnnotation(CreateIndex.class);

        final String documentId = parse(annotation.documentId(), joinPoint, String.class);
        final Field[] fields = annotation.fields();

        final Map<String, FieldMeta> meta = Arrays.stream(fields).collect(Collectors.toMap(Field::propertyName, f -> new FieldMeta(f.sort())));
        this.searchCore.indexMeta(annotation.index(), meta);
        
        for(Field field : fields) {
            this.searchCore.indexDocument(annotation.index(), field.propertyName(), documentId, parse(field.value(), joinPoint, String.class));
            if (field.sort()) {
                this.searchCore.indexSortField(annotation.index(), field.propertyName(), documentId, parse(field.value(), joinPoint, Double.class));
            }
        }

        return retVal;
    }

    @Around("updateDocumentAnnotationPointCut()")
    public Object updateDocument(ProceedingJoinPoint joinPoint) throws Throwable {
        Object retVal = joinPoint.proceed();

        final MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
        final UpdateDocument annotation = methodSignature.getMethod().getAnnotation(UpdateDocument.class);

        final String documentId = parse(annotation.documentId(), joinPoint, String.class);
        final String document = parse(annotation.document(), joinPoint, String.class);
        // final Field[] fields = annotation.fields();

        // this.searchCore.updateDocumentIndex(annotation.index(), ,documentId,
        // document);

        return retVal;
    }

    @Around("deleteDocumentAnnotationPointCut()")
    public Object delteDocuemnt(ProceedingJoinPoint joinPoint) throws Throwable {
        Object retVal = joinPoint.proceed();
        final MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
        final DeleteDocument annotation = methodSignature.getMethod().getAnnotation(DeleteDocument.class);
        final String documentId = parse(annotation.documentId(), joinPoint, String.class);
        this.searchCore.deleteDocumentIndex(annotation.index(), documentId);
        return retVal;
    }

    /**
     * 支持 #p0 参数索引的表达式解析
     *
     * @param spel 表达式
     * @return 解析后的字符串
     */
    private static <T> T parse(String spel, JoinPoint jp, Class<T> clazz) {
        // 获取被拦截方法参数名列表(使用Spring支持类库)
        LocalVariableTableParameterNameDiscoverer u = new LocalVariableTableParameterNameDiscoverer();
        // 使用SPEL进行key的解析
        ExpressionParser parser = new SpelExpressionParser();
        // SPEL上下文
        StandardEvaluationContext context = new MethodBasedEvaluationContext(jp.getTarget(),
                ((MethodSignature) jp.getSignature()).getMethod(), jp.getArgs(), u);
        return parser.parseExpression(spel).getValue(context, clazz);
    }
}
