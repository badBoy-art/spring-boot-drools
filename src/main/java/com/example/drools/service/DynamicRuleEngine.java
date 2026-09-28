package com.example.drools.service;

import com.example.drools.dao.RuleCombinationTableDao;
import com.example.drools.dao.RuleDefinitionDao;
import com.example.drools.entity.RuleCombinationTable;
import com.example.drools.entity.RuleDefinition;
import com.example.drools.http.HttpActionGateway;
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
 * 动态规则引擎：只从数据库加载规则，编译成 KieBase 并缓存。
 *
 * 两类来源（都是页面配置、库里存的纯数据，**没有任何 Excel 参与**）：
 *   1. rule_definition：③④ 配的单条规则，DRL 文本（或按类型模板现拼）；
 *   2. rule_combination_table：⑥ 组合规则表，页面表格 → 生成的 DRL 文本（一行组合 = 一条规则）。
 * 规则变更后调用 refresh() 重建缓存（原子切换 + dispose 旧容器，避免 Metaspace 泄漏）。
 */
@Component
public class DynamicRuleEngine implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(DynamicRuleEngine.class);

    /** 单次会话最多点火次数：规则写错不收敛时不要挂死业务线程 */
    private static final int MAX_FIRES = 200;

    private final RuleDefinitionDao ruleDefinitionDao;
    private final RuleCombinationTableDao combinationTableDao;
    private final DrlGenerator drlGenerator;
    /** 规则 RHS 里要用的动作网关（HTTP 调用）：以 Drools global 的形式注入每个会话 */
    private final HttpActionGateway httpActionGateway;

    private volatile KieContainer kieContainer;
    private volatile KieBase kieBase;
    private volatile int ruleCount;
    /** 最近一次加载规则集的失败原因 */
    private volatile String lastRefreshError;

    public DynamicRuleEngine(RuleDefinitionDao ruleDefinitionDao,
                             RuleCombinationTableDao combinationTableDao,
                             DrlGenerator drlGenerator, HttpActionGateway httpActionGateway) {
        this.ruleDefinitionDao = ruleDefinitionDao;
        this.combinationTableDao = combinationTableDao;
        this.drlGenerator = drlGenerator;
        this.httpActionGateway = httpActionGateway;
    }

    @Override
    public void afterPropertiesSet() {
        try {
            refresh();
        } catch (Exception e) {
            // 启动期某条历史规则编译不过（例如引用了已删除的 global / 手工改坏的 DRL）不应该让服务起不来：
            // 记明确错误 + 应用继续启动，规则暂不生效（运行期发布/刷新仍然快速失败，操作人立刻能看到原因）。
            this.lastRefreshError = e.getMessage();
            log.error("启动加载规则失败，应用继续启动但规则未生效：{}", e.getMessage());
        }
    }

    /** 最近一次规则集加载失败的原因（页面/接口可查），成功时清空 */
    public String getLastRefreshError() {
        return lastRefreshError;
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

    /** 从 DB 重载全部已发布规则（④ 的单条规则 DRL + ⑥ 组合规则表生成的 DRL），整体编译成功后原子替换缓存 */
    public synchronized void refresh() {
        List<RuleDefinition> rules = ruleDefinitionDao.findByStatus(1);
        List<RuleCombinationTable> combinations = combinationTableDao.findByStatus(1);
        if (rules.isEmpty() && combinations.isEmpty()) {
            log.warn("数据库中没有已发布(status=1)的规则/组合规则表，跳过刷新");
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
        int combinationRules = 0;
        for (RuleCombinationTable t : combinations) {
            String drl = t.getDrlContent();
            if (drl == null || drl.trim().isEmpty()) {
                log.warn("组合规则表[{}]已发布但没有 DRL 内容，跳过", t.getAssetKey());
                continue;
            }
            kfs.write("src/main/resources/rules/ct_" + t.getAssetKey() + ".drl", drl);
            combinationRules += t.getRowCount() == null ? 0 : t.getRowCount();
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
        this.ruleCount = countRules(newBase);
        this.lastRefreshError = null;
        if (old != null) {
            old.dispose();
        }
        log.info("规则引擎已刷新：DRL 规则 {} 条 + 组合规则表 {} 条，KieBase 内规则总数 {}",
                rules.size(), combinationRules, this.ruleCount);
    }

    private int countRules(KieBase base) {
        int count = 0;
        for (org.kie.api.definition.KiePackage pkg : base.getKiePackages()) {
            count += pkg.getRules().size();
        }
        return count;
    }

    /**
     * 统一的点火入口：带上限（防止规则写错不收敛，把业务线程 100% CPU 挂死）。
     * 返回实际点火次数，达到上限会告警。
     */
    public int fireAllRules(KieSession session) {
        int fired = session.fireAllRules(MAX_FIRES);
        if (fired >= MAX_FIRES) {
            log.warn("规则点火次数达到上限 {}，疑似规则不收敛（检查 update() 后是否缺少状态守卫、" +
                    "或 mvel 方言 RHS 里是否调用了非 setter 方法）", MAX_FIRES);
        }
        return fired;
    }

    public KieSession newKieSession() {
        KieBase base = this.kieBase;
        if (base == null) {
            throw new IllegalStateException("规则引擎尚未初始化，请先发布规则");
        }
        KieSession session = base.newKieSession();
        // 规则里声明的 global 必须在每个会话上赋值，否则用到它的规则一执行就 NPE。
        // 这里注入的是 HTTP 动作网关 —— 规则因此可以按配置调用任意 HTTP 接口。
        session.setGlobal("httpActionGateway", httpActionGateway);
        return session;
    }

    public int getRuleCount() {
        return ruleCount;
    }

    /** 数据库里 status=1 的规则条数（不含决策表展开出来的规则），用于和 KieBase 内的总数区分 */
    public int getPublishedRuleCount() {
        return ruleDefinitionDao.findByStatus(1).size();
    }
}
