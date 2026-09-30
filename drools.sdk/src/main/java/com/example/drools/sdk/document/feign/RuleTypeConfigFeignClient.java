package com.example.drools.sdk.document.feign;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Feign contract exposed by the Rule Center for a document's runtime rule-type bundle. */
@FeignClient(
    name = "drools-rule-center",
    url = "${drools.rule-center.url}",
    configuration = RuleCenterFeignConfiguration.class)
public interface RuleTypeConfigFeignClient {
  @GetMapping("/rule/type/runtime")
  JsonNode getRuntimeRuleType(@RequestParam("ruleType") String ruleType);
}
