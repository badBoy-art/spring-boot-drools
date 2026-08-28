package com.example.drools.service;

import com.example.drools.dao.RuleDefinitionDao;
import com.example.drools.entity.RuleDefinition;
import org.kie.api.KieBase;
import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.builder.Message;
import org.kie.api.builder.Results;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 动态规则引擎：从数据库加载已发布规则的 DRL，整体编译成 KieBase 并缓存。
 * 规则变更后调用 refresh() 重建缓存（原子切换 + dispose 旧容器，避免 Metaspace 泄漏）。
 */
@Component
public class DynamicRuleEngine implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(DynamicRuleEngine.class);

    private final RuleDefinitionDao ruleDefinitionDao;
    private final DrlGenerator drlGenerator;

    private volatile KieContainer kieContainer;
    private volatile KieBase kieBase;
    private volatile int ruleCount;

    public DynamicRuleEngine(RuleDefinitionDao ruleDefinitionDao, DrlGenerator drlGenerator) {
        this.ruleDefinitionDao = ruleDefinitionDao;
        this.drlGenerator = drlGenerator;
    }

    @Override
    public void afterPropertiesSet() {
        refresh();
    }

    /** 试编译单条 DRL（不改变当前缓存），失败抛异常，用于发布前的校验 */
    public void validate(String drl) {
        KieServices ks = KieServices.Factory.get();
        KieFileSystem kfs = ks.newKieFileSystem();
        kfs.write("src/main/resources/validate.drl", drl);
        Results results = ks.newKieBuilder(kfs).buildAll().getResults();
        if (results.hasMessages(Message.Level.ERROR)) {
            throw new IllegalStateException("规则编译失败: " + results.getMessages());
        }
    }

    /** 从 DB 重载全部已发布规则，整体编译成功后原子替换缓存 */
    public synchronized void refresh() {
        List<RuleDefinition> rules = ruleDefinitionDao.findByStatus(1);
        if (rules.isEmpty()) {
            log.warn("数据库中没有已发布(status=1)的规则，跳过刷新");
            return;
        }

        KieServices ks = KieServices.Factory.get();
        KieFileSystem kfs = ks.newKieFileSystem();
        for (RuleDefinition r : rules) {
            String drl = r.getDrlContent();
            if (drl == null || drl.trim().isEmpty()) {
                drl = drlGenerator.generate(r);
            }
            kfs.write("src/main/resources/rules/" + r.getRuleName() + ".drl", drl);
        }

        KieBuilder builder = ks.newKieBuilder(kfs);
        builder.buildAll();
        if (builder.getResults().hasMessages(Message.Level.ERROR)) {
            throw new IllegalStateException("规则集编译失败: " + builder.getResults().getMessages());
        }

        KieContainer newContainer = ks.newKieContainer(ks.getRepository().getDefaultReleaseId());
        KieBase newBase = newContainer.getKieBase();

        KieContainer old = this.kieContainer;
        this.kieBase = newBase;
        this.kieContainer = newContainer;
        this.ruleCount = rules.size();
        if (old != null) {
            old.dispose();
        }
        log.info("规则引擎已刷新，{} 条规则生效", rules.size());
    }

    public KieSession newKieSession() {
        KieBase base = this.kieBase;
        if (base == null) {
            throw new IllegalStateException("规则引擎尚未初始化，请先发布规则");
        }
        return base.newKieSession();
    }

    public int getRuleCount() {
        return ruleCount;
    }
}
