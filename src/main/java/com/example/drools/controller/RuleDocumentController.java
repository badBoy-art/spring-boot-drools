package com.example.drools.controller;

import com.example.drools.dao.RuleDocumentDao;
import com.example.drools.entity.RuleDocument;
import com.example.drools.entity.RuleDocumentField;
import com.example.drools.entity.RuleDocumentObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单据接入规则引擎中台：注册单据、单据对象（可嵌套）、对象字段（中文名 + fieldName + 说明）。
 * 这些元数据是"配置期"的契约：规则参数、HTTP 接口入参都按它来挑字段。
 */
@RestController
@RequestMapping("/rule/doc")
public class RuleDocumentController {

    private final RuleDocumentDao dao;

    public RuleDocumentController(RuleDocumentDao dao) {
        this.dao = dao;
    }

    /** 单据清单（含对象树 + 字段） */
    @GetMapping("/tree")
    public List<Map<String, Object>> tree() {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (RuleDocument doc : dao.findDocuments()) {
            result.add(treeOf(doc));
        }
        return result;
    }

    /** 兼容旧调用：单据 + 平铺字段 */
    @GetMapping("/list")
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (RuleDocument doc : dao.findDocuments()) {
            Map<String, Object> node = treeOf(doc);
            node.put("fields", fields(doc.getDocCode()));
            result.add(node);
        }
        return result;
    }

    /** 某单据的对象清单 */
    @GetMapping("/objects")
    public List<RuleDocumentObject> objects(@RequestParam String docCode) {
        return dao.findObjects(docCode);
    }

    /** 某单据的字段清单（页面字段选择器用） */
    @GetMapping("/fields")
    public List<RuleDocumentField> fields(@RequestParam String docCode) {
        return dao.findFields(docCode);
    }

    /** 注册/修改单据（docCode + 单据中文名 + 说明） */
    @PostMapping("/save")
    public Object save(@RequestBody RuleDocument doc) {
        require(doc.getDocCode(), "单据编码(docCode)必填");
        require(doc.getDocName(), "单据名称(docName)必填");
        if (dao.findDocument(doc.getDocCode()) == null) {
            dao.insertDocument(doc.getDocCode(), doc.getDocName(), doc.getRemark());
        } else {
            dao.updateDocument(doc.getDocCode(), doc.getDocName(), doc.getRemark());
        }
        return dao.findDocument(doc.getDocCode());
    }

    /** 注册/修改单据对象（可嵌套：parentObjectKey 指向父对象；数组对象 isCollection=1） */
    @PostMapping("/object/save")
    public Object saveObject(@RequestBody RuleDocumentObject object) {
        require(object.getDocCode(), "单据编码(docCode)必填");
        require(object.getObjectKey(), "对象标识(objectKey)必填");
        require(object.getObjectName(), "对象中文名(objectName)必填");
        if (dao.findDocument(object.getDocCode()) == null) {
            throw new IllegalArgumentException("单据不存在: " + object.getDocCode() + "（请先注册单据）");
        }
        if (object.getParentObjectKey() != null && !object.getParentObjectKey().trim().isEmpty()) {
            boolean parentExists = false;
            for (RuleDocumentObject o : dao.findObjects(object.getDocCode())) {
                if (o.getObjectKey().equals(object.getParentObjectKey())) {
                    parentExists = true;
                    break;
                }
            }
            if (!parentExists) {
                throw new IllegalArgumentException("父对象不存在: " + object.getParentObjectKey());
            }
        }
        dao.upsertObject(object);
        return objects(object.getDocCode());
    }

    /** 注册/修改单据字段（所属对象 + 中文名 + fieldName + 说明 + 示例值） */
    @PostMapping("/field/save")
    public Object saveField(@RequestBody RuleDocumentField field) {
        require(field.getDocCode(), "单据编码(docCode)必填");
        require(field.getFieldKey(), "字段取值路径(fieldKey)必填");
        require(field.getFieldName(), "字段中文名(fieldName)必填");
        if (dao.findDocument(field.getDocCode()) == null) {
            throw new IllegalArgumentException("单据不存在: " + field.getDocCode());
        }
        if (field.getObjectKey() == null || field.getObjectKey().trim().isEmpty()) {
            throw new IllegalArgumentException("所属对象(objectKey)必填（字段要挂在单据的某个对象下）");
        }
        boolean objectExists = false;
        for (RuleDocumentObject o : dao.findObjects(field.getDocCode())) {
            if (o.getObjectKey().equals(field.getObjectKey())) {
                objectExists = true;
                break;
            }
        }
        if (!objectExists) {
            throw new IllegalArgumentException("单据[" + field.getDocCode() + "]下没有对象: " + field.getObjectKey()
                    + "（请先注册对象，或选已有的对象）");
        }
        if (field.getFieldType() == null || field.getFieldType().trim().isEmpty()) {
            field.setFieldType("STRING");
        }
        dao.upsertField(field);
        return fields(field.getDocCode());
    }

    @PostMapping("/field/delete")
    public Object deleteField(@RequestBody RuleDocumentField field) {
        dao.deleteField(field.getDocCode(), field.getFieldKey());
        return fields(field.getDocCode());
    }

    // ------------------------------------------------------------------ 组装

    private Map<String, Object> treeOf(RuleDocument doc) {
        List<RuleDocumentObject> objects = dao.findObjects(doc.getDocCode());
        List<RuleDocumentField> fields = dao.findFields(doc.getDocCode());
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("docCode", doc.getDocCode());
        root.put("docName", doc.getDocName());
        root.put("remark", doc.getRemark());
        root.put("objects", objects);
        root.put("fieldCount", fields.size());
        root.put("tree", buildChildren(objects, fields, null));
        return root;
    }

    /** 按 parentObjectKey 递归组装对象树，并把字段挂到各自对象下 */
    private List<Map<String, Object>> buildChildren(List<RuleDocumentObject> objects, List<RuleDocumentField> fields,
                                                    String parentKey) {
        List<Map<String, Object>> nodes = new ArrayList<Map<String, Object>>();
        for (RuleDocumentObject object : objects) {
            String parent = object.getParentObjectKey() == null || object.getParentObjectKey().trim().isEmpty()
                    ? null : object.getParentObjectKey();
            boolean match = parentKey == null ? parent == null : parentKey.equals(parent);
            if (!match) {
                continue;
            }
            Map<String, Object> node = new LinkedHashMap<String, Object>();
            node.put("objectKey", object.getObjectKey());
            node.put("objectName", object.getObjectName());
            node.put("isCollection", object.getIsCollection());
            node.put("valuePath", object.getValuePath());
            node.put("remark", object.getRemark());
            List<RuleDocumentField> own = new ArrayList<RuleDocumentField>();
            for (RuleDocumentField field : fields) {
                if (object.getObjectKey().equals(field.getObjectKey())) {
                    own.add(field);
                }
            }
            node.put("fields", own);
            node.put("children", buildChildren(objects, fields, object.getObjectKey()));
            nodes.add(node);
        }
        return nodes;
    }

    private void require(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(message);
        }
    }
}
