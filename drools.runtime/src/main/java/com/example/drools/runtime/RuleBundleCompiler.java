package com.example.drools.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.drools.modelcompiler.ExecutableModelProject;
import org.kie.api.KieBaseConfiguration;
import org.kie.api.KieServices;
import org.kie.api.builder.*;
import org.kie.api.io.Resource;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieContainer;
import org.kie.internal.builder.DecisionTableConfiguration;
import org.kie.internal.builder.DecisionTableInputType;
import org.kie.internal.builder.KnowledgeBuilderFactory;

/** Common authoring/runtime compiler. Resource types are delegated to native KIE assemblers. */
public class RuleBundleCompiler {
  private static final Object BUILD_LOCK = new Object();
  private final KieServices services = KieServices.Factory.get();

  public CompiledRuleBundle compile(JsonNode bundle, KieBaseConfiguration configuration) {
    synchronized (BUILD_LOCK) {
      KieContainer container = null;
      try {
        for (JsonNode dependency : bundle.path("dependencyKjars")) {
          services
              .getRepository()
              .addKieModule(
                  services
                      .getResources()
                      .newByteArrayResource(Base64.getDecoder().decode(dependency.asText())));
        }
        ReleaseId id;
        if (bundle.hasNonNull("artifact")) {
          JsonNode artifact = bundle.get("artifact");
          id =
              services.newReleaseId(
                  required(artifact, "groupId"),
                  required(artifact, "artifactId"),
                  required(artifact, "version"));
        } else if (bundle.hasNonNull("kjarBase64")) {
          Resource jar =
              services
                  .getResources()
                  .newByteArrayResource(
                      Base64.getDecoder().decode(bundle.get("kjarBase64").asText()));
          id = services.getRepository().addKieModule(jar).getReleaseId();
        } else {
          KieFileSystem fs = services.newKieFileSystem();
          id =
              services.newReleaseId(
                  "com.example.rulecenter", "bundle", UUID.randomUUID().toString());
          fs.generateAndWritePomXML(id);
          if (bundle.hasNonNull("kmoduleXml"))
            fs.writeKModuleXML(bundle.get("kmoduleXml").asText());
          // A Maven dependency declaration is an asset, not something reconstructed by the rule
          // form.
          if (bundle.hasNonNull("pomXml")) {
            fs.writePomXML(uniquePom(bundle.get("pomXml").asText(), id));
          }
          int count = 0;
          for (JsonNode rule : bundle.path("rules")) {
            String drl = required(rule, "drl");
            fs.write("src/main/resources/rules/legacy-" + (count++) + ".drl", drl);
          }
          for (JsonNode asset : bundle.path("resources")) {
            String path = required(asset, "path");
            if (!(path.startsWith("src/main/resources/") || path.startsWith("src/main/java/"))
                || path.contains("..")
                || path.contains("\\"))
              throw new IllegalArgumentException(
                  "Resource path must be inside src/main/resources or src/main/java: " + path);
            byte[] bytes =
                asset.hasNonNull("base64")
                    ? Base64.getDecoder().decode(asset.get("base64").asText())
                    : required(asset, "content").getBytes(StandardCharsets.UTF_8);
            Resource resource =
                services.getResources().newByteArrayResource(bytes).setSourcePath(path);
            if (asset.hasNonNull("resourceType")) {
              ResourceType type = ResourceType.getResourceType(asset.get("resourceType").asText());
              if (type == null)
                throw new IllegalArgumentException("Unknown native KIE resourceType");
              resource.setResourceType(type);
              if (ResourceType.DTABLE.equals(type)) {
                DecisionTableConfiguration dt =
                    KnowledgeBuilderFactory.newDecisionTableConfiguration();
                dt.setInputType(
                    DecisionTableInputType.valueOf(asset.path("inputType").asText("XLS")));
                if (asset.hasNonNull("worksheetName"))
                  dt.setWorksheetName(asset.get("worksheetName").asText());
                resource.setConfiguration(dt);
              }
            }
            fs.write(path, resource);
            count++;
          }
          if (count == 0) fs.write("src/main/resources/empty.drl", "package platform.empty;\n");
          KieBuilder builder =
              services.newKieBuilder(fs, Thread.currentThread().getContextClassLoader());
          if (bundle.path("executableModel").asBoolean())
            builder.buildAll(ExecutableModelProject.class);
          else builder.buildAll();
          if (builder.getResults().hasMessages(Message.Level.ERROR))
            throw new IllegalArgumentException(
                "Rule bundle compilation failed: "
                    + builder.getResults().getMessages(Message.Level.ERROR));
          id = builder.getKieModule().getReleaseId();
        }
        container = services.newKieContainer(id);
        org.kie.api.KieBase base =
            bundle.hasNonNull("kieBaseName")
                ? container.newKieBase(bundle.get("kieBaseName").asText(), configuration)
                : container.newKieBase(configuration);
        return new CompiledRuleBundle(container, base);
      } catch (RuntimeException e) {
        if (container != null) container.dispose();
        throw e;
      }
    }
  }

  private static String required(JsonNode node, String name) {
    String value = node.path(name).asText();
    if (value.trim().isEmpty()) throw new IllegalArgumentException(name + " is required");
    return value;
  }

  private String uniquePom(String source, ReleaseId id) {
    try {
      javax.xml.parsers.DocumentBuilderFactory factory =
          javax.xml.parsers.DocumentBuilderFactory.newInstance();
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      org.w3c.dom.Document doc =
          factory
              .newDocumentBuilder()
              .parse(new java.io.ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)));
      String[] names = {"groupId", "artifactId", "version"};
      String[] values = {id.getGroupId(), id.getArtifactId(), id.getVersion()};
      for (int i = 0; i < names.length; i++) {
        org.w3c.dom.Element target = null;
        for (org.w3c.dom.Node n = doc.getDocumentElement().getFirstChild();
            n != null;
            n = n.getNextSibling())
          if (n instanceof org.w3c.dom.Element && names[i].equals(n.getNodeName()))
            target = (org.w3c.dom.Element) n;
        if (target == null) {
          target = doc.createElement(names[i]);
          doc.getDocumentElement().appendChild(target);
        }
        target.setTextContent(values[i]);
      }
      java.io.StringWriter writer = new java.io.StringWriter();
      javax.xml.transform.TransformerFactory.newInstance()
          .newTransformer()
          .transform(
              new javax.xml.transform.dom.DOMSource(doc),
              new javax.xml.transform.stream.StreamResult(writer));
      return writer.toString();
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid Maven POM", e);
    }
  }
}
