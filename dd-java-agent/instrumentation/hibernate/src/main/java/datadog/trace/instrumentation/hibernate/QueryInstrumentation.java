package datadog.trace.instrumentation.hibernate;

import static datadog.trace.agent.tooling.ByteBuddyElementMatchers.safeHasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.hibernate.Query;

@AutoService(Instrumenter.class)
public class QueryInstrumentation extends Instrumenter.Default {

  public QueryInstrumentation() {
    super("hibernate");
  }

  @Override
  public Map<String, String> contextStore() {
    final Map<String, String> map = new HashMap<>();
    map.put("org.hibernate.Query", SessionState.class.getName());
    return Collections.unmodifiableMap(map);
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.hibernate.SessionMethodUtils",
      "datadog.trace.instrumentation.hibernate.SessionState",
    };
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return not(isInterface()).and(safeHasSuperType(named("org.hibernate.Query")));
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    final Map<ElementMatcher<? super MethodDescription>, String> transformers = new HashMap<>();
    transformers.put(
        isMethod()
            .and(
                named("list")
                    .or(named("executeUpdate"))
                    .or(named("uniqueResult"))
                    .or(named("iterate"))
                    .or(named("scroll"))),
        QueryMethodAdvice.class.getName());

    return transformers;
  }

  public static class QueryMethodAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static SessionState startMethod(
        @Advice.This final Query query, @Advice.Origin("#m") final String name) {

      final ContextStore<Query, SessionState> contextStore =
          InstrumentationContext.get(Query.class, SessionState.class);

      return SessionMethodUtils.startScopeFrom(contextStore, query, "hibernate.query." + name);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void endMethod(
        @Advice.This final Query query,
        @Advice.Enter final SessionState state,
        @Advice.Thrown final Throwable throwable) {

      SessionMethodUtils.closeScope(state, throwable);
    }
  }
}
