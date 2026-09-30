package com.example.drools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.drools.dao.RuleDefinitionDao;
import com.example.drools.dao.RuleTypeMetaDao;
import com.example.drools.entity.RuleDefinition;
import com.example.drools.entity.RuleTypeMeta;
import com.example.drools.service.DrlGenerator;
import com.example.drools.service.DrlValidator;
import com.example.drools.service.RuleDefinitionService;
import com.example.drools.service.RuleParamValidator;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class NativeRuleDefinitionServiceTest {
  @Test
  void nativeSourceIsPreservedAndGenericPublisherCannotRewriteIt() {
    RuleDefinitionDao dao = mock(RuleDefinitionDao.class);
    RuleTypeMetaDao metaDao = mock(RuleTypeMetaDao.class);
    DrlGenerator generator = mock(DrlGenerator.class);
    DrlValidator engine = new DrlValidator();
    RuleTypeMeta meta = new RuleTypeMeta();
    meta.setRuleType("NATIVE_TEST");
    meta.setRuleGroup("advanced");
    when(metaDao.findByType("NATIVE_TEST")).thenReturn(meta);
    AtomicReference<RuleDefinition> stored = new AtomicReference<RuleDefinition>();
    doAnswer(
            invocation -> {
              RuleDefinition rule = invocation.getArgument(0);
              rule.setId(7L);
              stored.set(rule);
              return null;
            })
        .when(dao)
        .insert(any(RuleDefinition.class));
    when(dao.findById(7L)).thenAnswer(invocation -> stored.get());
    doAnswer(
            invocation -> {
              stored.get().setStatus(invocation.getArgument(1));
              return 1;
            })
        .when(dao)
        .updateStatus(7L, 1);
    when(dao.findByStatus(1))
        .thenAnswer(
            invocation ->
                stored.get().getStatus() == 1
                    ? Collections.singletonList(stored.get())
                    : Collections.<RuleDefinition>emptyList());

    RuleDefinitionService service =
        new RuleDefinitionService(
            dao,
            metaDao,
            generator,
            engine,
            mock(RuleParamValidator.class),
            mock(com.example.drools.release.RuleReleaseService.class));
    String source = "package advanced.check;\nrule \"native-source\" when then end\n";
    RuleDefinition request = new RuleDefinition();
    request.setRuleType("NATIVE_TEST");
    request.setRuleName("NATIVE_SOURCE_TEST");
    request.setDrlContent(source);

    RuleDefinition draft = service.createNative(request);
    assertEquals(source, RuleDefinitionService.stripNativeMarker(draft.getDrlContent()));
    service.publishNative(draft.getId(), source, 0L);
    assertEquals(1, stored.get().getStatus());
    assertEquals(source, RuleDefinitionService.stripNativeMarker(stored.get().getDrlContent()));
    assertThrows(IllegalArgumentException.class, () -> service.publish(7L, "{}", 0L));
    verify(generator, never()).generate(any(RuleDefinition.class));
  }
}
