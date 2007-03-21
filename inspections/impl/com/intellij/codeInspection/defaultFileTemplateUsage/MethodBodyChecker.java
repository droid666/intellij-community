package com.intellij.codeInspection.defaultFileTemplateUsage;

import com.intellij.codeInsight.PsiEquivalenceUtil;
import com.intellij.codeInsight.daemon.impl.quickfix.CreateFromUsageUtils;
import com.intellij.codeInsight.generation.OverrideImplementUtil;
import com.intellij.codeInspection.*;
import com.intellij.ide.fileTemplates.FileTemplate;
import com.intellij.ide.fileTemplates.FileTemplateManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Pair;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.IncorrectOperationException;
import gnu.trove.THashMap;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * @author Alexey
 */
public class MethodBodyChecker {
  private static final Logger LOG = Logger.getInstance("#com.intellij.codeInspection.defaultFileTemplateUsage.MethodBodyChecker");

  // return type canonical name + superMethodName + file template name -> method template
  private static PsiClassType OBJECT_TYPE;

  private static PsiMethod getTemplateMethod(PsiType returnType, List<HierarchicalMethodSignature> superSignatures, final PsiClass aClass) {
    Project project = aClass.getProject();

    if (!(returnType instanceof PsiPrimitiveType)) {
      returnType = PsiType.getJavaLangObject(PsiManager.getInstance(project), GlobalSearchScope.allScope(project));
    }
    try {
      final String fileTemplateName = getMethodFileTemplate(superSignatures, true).getName();
      String methodName = superSignatures.isEmpty() ? "" : superSignatures.get(0).getName();
      String key = returnType.getCanonicalText() + "+" + methodName + "+"+fileTemplateName;
      final Map<String, PsiMethod> cache = getTemplatesCache(project);
      PsiMethod method = cache.get(key);
      if (method == null) {
        method = PsiManager.getInstance(project).getElementFactory().createMethod("x", returnType);
        setupMethodBody(superSignatures, method, aClass, true);
        cache.put(key, method);
      }
      return method;
    }
    catch (IncorrectOperationException e) {
      LOG.error(e);
      return null;
    }
  }

  private static final Key<Map<String, PsiMethod>> CACHE_IN_PROJECT_KEY = new Key<Map<String, PsiMethod>>("MethodBodyChecker templates cache");

  private static Map<String, PsiMethod> getTemplatesCache(Project project) {
    Map<String, PsiMethod> cache = project.getUserData(CACHE_IN_PROJECT_KEY);
    if (cache == null) {
      cache = new THashMap<String, PsiMethod>();
      project.putUserData(CACHE_IN_PROJECT_KEY, cache);
    }
    return cache;
  }

  static void checkMethodBody(final PsiMethod method,
                              final InspectionManager manager,
                              final Collection<ProblemDescriptor> problemDescriptors) {
    PsiType returnType = method.getReturnType();
    if (method.isConstructor() || returnType == null) return;
    PsiCodeBlock body = method.getBody();
    if (body == null) return;
    PsiClass aClass = method.getContainingClass();
    if (aClass == null || aClass.isInterface()) return;
    List<HierarchicalMethodSignature> superSignatures = method.getHierarchicalMethodSignature().getSuperSignatures();
    final PsiMethod superMethod = superSignatures.isEmpty() ? null : superSignatures.get(0).getMethod();
    final PsiMethod templateMethod = getTemplateMethod(returnType, superSignatures, aClass);
    if (PsiEquivalenceUtil.areElementsEquivalent(body, templateMethod.getBody(), new Comparator<PsiElement>(){
      public int compare(final PsiElement element1, final PsiElement element2) {
        // templates may be different on super method name                              
        if (element1 == superMethod && (element2 == templateMethod || element2 == null)) return 0;
        return 1;
      }
    }, true)) {
      Pair<? extends PsiElement, ? extends PsiElement> range = DefaultFileTemplateUsageInspection.getInteriorRange(body);
      final String description = InspectionsBundle.message("default.file.template.description");
      ProblemDescriptor problem = manager.createProblemDescriptor(range.first, range.second, description,
                                                                  ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                                                                  createMethodBodyQuickFix(method));
      problemDescriptors.add(problem);
    }
  }

  private static FileTemplate getMethodFileTemplate(final List<HierarchicalMethodSignature> superSignatures,
                                                    final boolean useDefaultTemplate) {
    FileTemplateManager templateManager = FileTemplateManager.getInstance();
    FileTemplate template;
    if (superSignatures.isEmpty()) {
      String name = FileTemplateManager.TEMPLATE_FROM_USAGE_METHOD_BODY;
      template = useDefaultTemplate ? templateManager.getDefaultTemplate(name) : templateManager.getCodeTemplate(name);
    }
    else {
      PsiMethod superMethod = superSignatures.get(0).getMethod();
      String name = superMethod.hasModifierProperty(PsiModifier.ABSTRACT) ?
                    FileTemplateManager.TEMPLATE_IMPLEMENTED_METHOD_BODY : FileTemplateManager.TEMPLATE_OVERRIDDEN_METHOD_BODY;
      template = useDefaultTemplate ? templateManager.getDefaultTemplate(name) : templateManager.getCodeTemplate(name);
    }
    return template;
  }

  private static final String NEW_METHOD_BODY_TEMPLATE_NAME = FileTemplateManager.getInstance().getDefaultTemplate(FileTemplateManager.TEMPLATE_FROM_USAGE_METHOD_BODY).getName();
  private static FileTemplate setupMethodBody(final List<HierarchicalMethodSignature> superSignatures,
                                              final PsiMethod templateMethod,
                                              final PsiClass aClass,
                                              final boolean useDefaultTemplate) throws IncorrectOperationException {
    FileTemplate template = getMethodFileTemplate(superSignatures, useDefaultTemplate);
    if (NEW_METHOD_BODY_TEMPLATE_NAME.equals(template.getName())) {
      CreateFromUsageUtils.setupMethodBody(templateMethod, aClass, template);
    }
    else {
      PsiMethod superMethod = superSignatures.get(0).getMethod();
      OverrideImplementUtil.setupMethodBody(templateMethod, superMethod, aClass,template);
    }
    return template;
  }

  private static LocalQuickFix[] createMethodBodyQuickFix(final PsiMethod method) {
    PsiType returnType = method.getReturnType();
    PsiClass aClass = method.getContainingClass();
    List<HierarchicalMethodSignature> superSignatures = method.getHierarchicalMethodSignature().getSuperSignatures();
    FileTemplate template;
    try {
      PsiMethod templateMethod = method.getManager().getElementFactory().createMethod("x", returnType);
      template = setupMethodBody(superSignatures, templateMethod, aClass, false);
    }
    catch (IncorrectOperationException e) {
      LOG.error(e);
      return null;
    }
    final ReplaceWithFileTemplateFix replaceWithFileTemplateFix = new ReplaceWithFileTemplateFix() {
      public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        PsiType returnType = method.getReturnType();
        if (method.isConstructor() || returnType == null) return;
        PsiCodeBlock body = method.getBody();
        if (body == null) return;
        PsiClass aClass = method.getContainingClass();
        if (aClass == null) return;
        List<HierarchicalMethodSignature> superSignatures = method.getHierarchicalMethodSignature().getSuperSignatures();
        try {
          PsiMethod templateMethod = method.getManager().getElementFactory().createMethod("x", returnType);
          setupMethodBody(superSignatures, templateMethod, aClass, false);
          PsiElement newBody = method.getBody().replace(templateMethod.getBody());
          CodeStyleManager.getInstance(project).reformat(newBody);
        }
        catch (IncorrectOperationException e) {
          LOG.error(e);
        }
      }
    };
    LocalQuickFix editFileTemplateFix = DefaultFileTemplateUsageInspection.createEditFileTemplateFix(template, replaceWithFileTemplateFix);
    if (template.isDefault()) {
      return new LocalQuickFix[]{editFileTemplateFix};
    }
    return new LocalQuickFix[]{replaceWithFileTemplateFix, editFileTemplateFix};
  }
}
