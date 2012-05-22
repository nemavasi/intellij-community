package com.jetbrains.python.psi.types;

import com.intellij.codeInsight.completion.CompletionUtil;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiInvalidElementAccessException;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.ArrayUtil;
import com.intellij.util.ProcessingContext;
import com.intellij.util.containers.ContainerUtil;
import com.jetbrains.python.PyNames;
import com.jetbrains.python.codeInsight.PyDynamicMember;
import com.jetbrains.python.psi.*;
import com.jetbrains.python.psi.impl.PyBuiltinCache;
import com.jetbrains.python.psi.impl.PyClassImpl;
import com.jetbrains.python.psi.impl.PyTypeProvider;
import com.jetbrains.python.psi.impl.ResolveResultList;
import com.jetbrains.python.psi.resolve.*;
import com.jetbrains.python.psi.stubs.PyClassNameIndex;
import com.jetbrains.python.toolbox.Maybe;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * @author yole
 */
public class PyClassType extends UserDataHolderBase implements PyCallableType {

  @Nullable protected final PyClass myClass;
  protected final boolean myIsDefinition;

  private static ThreadLocal<Set<Pair<PyClass, String>>> ourResolveMemberStack = new ThreadLocal<Set<Pair<PyClass, String>>>() {
    @Override
    protected Set<Pair<PyClass, String>> initialValue() {
      return new HashSet<Pair<PyClass, String>>();
    }
  };

  /**
   * Describes a class-based type. Since everything in Python is an instance of some class, this type pretty much completes
   * the type system :)
   * Note that classes' and instances' member list can change during execution, so it is important to construct an instance of PyClassType
   * right in the place of reference, so that such changes could possibly be accounted for.
   *
   * @param source        PyClass which defines this type. For builtin or external classes, skeleton files contain the definitions.
   * @param is_definition whether this type describes an instance or a definition of the class.
   */
  public PyClassType(@Nullable PyClass source, boolean is_definition) {
    myClass = source != null ? CompletionUtil.getOriginalElement(source) : null;
    myIsDefinition = is_definition;
  }

  public PyClassType(@NotNull Project project, String classQualifiedName, boolean isDefinition) {
    myClass = PyClassNameIndex.findClass(classQualifiedName, project);
    myIsDefinition = isDefinition;
  }

  public <T> PyClassType withUserData(Key<T> key, T value) {
    putUserData(key, value);
    return this;
  }

  /**
   * @return a PyClass which defined this type.
   */
  @Nullable
  public PyClass getPyClass() {
    return myClass;
  }

  /**
   * @return whether this type refers to an instance or a definition of the class.
   */
  public boolean isDefinition() {
    return myIsDefinition;
  }

  public PyClassType toInstance() {
    return myIsDefinition ? new PyClassType(myClass, false) : this;
  }

  @Nullable
  public String getClassQName() {
    return myClass == null ? null : myClass.getQualifiedName();
  }

  @Nullable
  public List<? extends RatedResolveResult> resolveMember(final String name, @Nullable PyExpression location, AccessDirection direction,
                                                          PyResolveContext resolveContext) {
    if (myClass == null) return null;
    final Set<Pair<PyClass, String>> resolving = ourResolveMemberStack.get();
    final Pair<PyClass, String> key = Pair.create(myClass, name);
    if (resolving.contains(key)) {
      return Collections.emptyList();
    }
    resolving.add(key);
    try {
      return doResolveMember(name, location, direction, resolveContext);
    }
    finally {
      resolving.remove(key);
    }
  }

  @Nullable
  private List<? extends RatedResolveResult> doResolveMember(String name,
                                                             PyExpression location,
                                                             AccessDirection direction,
                                                             PyResolveContext resolveContext) {
    if (myClass == null) {
      return null;
    }
    if (resolveContext.allowProperties()) {
      Property property = myClass.findProperty(name);
      if (property != null) {
        Maybe<PyFunction> accessor = property.getByDirection(direction);
        if (accessor.isDefined()) {
          Callable accessor_code = accessor.value();
          ResolveResultList ret = new ResolveResultList();
          if (accessor_code != null) ret.poke(accessor_code, RatedResolveResult.RATE_NORMAL);
          PyTargetExpression site = property.getDefinitionSite();
          if (site != null) ret.poke(site, RatedResolveResult.RATE_LOW);
          if (ret.size() > 0) {
            return ret;
          }
          else {
            return null;
          } // property is found, but the required accessor is explicitly absent
        }
      }
    }

    if ("super".equals(getClassQName()) && isBuiltin(resolveContext.getTypeEvalContext()) && location instanceof PyCallExpression) {
      // methods of super() call are not of class super!
      PyExpression first_arg = ((PyCallExpression)location).getArgument(0, PyExpression.class);
      if (first_arg != null) { // the usual case: first arg is the derived class that super() is proxying for
        PyType first_arg_type = first_arg.getType(resolveContext.getTypeEvalContext());
        if (first_arg_type instanceof PyClassType) {
          PyClass derived_class = ((PyClassType)first_arg_type).getPyClass();
          if (derived_class != null) {
            final Iterator<PyClass> base_it = derived_class.iterateAncestorClasses().iterator();
            if (base_it.hasNext()) {
              return new PyClassType(base_it.next(), true).resolveMember(name, location, direction, resolveContext);
            }
            else return null; // no base classes = super() cannot proxy anything meaningful from a base class
          }
        }
      }
    }

    final PsiElement classMember = resolveClassMember(this, name, location);
    if (classMember != null) {
      return ResolveResultList.to(classMember);
    }

    for (PyClassRef superClass : myClass.iterateAncestors()) {
      final PyClass pyClass = superClass.getPyClass();
      if (pyClass != null) {
        PsiElement superMember = resolveClassMember(new PyClassType(pyClass, isDefinition()), name, null);
        if (superMember != null) {
          return ResolveResultList.to(superMember);
        }
      }
      else {
        final PsiElement element = superClass.getElement();
        if (element != null) {
          for (PyTypeProvider typeProvider : Extensions.getExtensions(PyTypeProvider.EP_NAME)) {
            final PyType refType = typeProvider.getReferenceType(element, resolveContext.getTypeEvalContext(), myClass);
            if (refType != null) {
              return refType.resolveMember(name, location, direction, resolveContext);
            }
          }
        }
      }
    }
    if (isDefinition() && myClass.isNewStyleClass()) {
      PyClassType typeType = getMetaclassType();
      if (typeType != null) {
        List<? extends RatedResolveResult> typeMembers = typeType.resolveMember(name, location, direction, resolveContext);
        if (typeMembers != null && !typeMembers.isEmpty()) {
          return typeMembers;
        }
      }
    }
    return Collections.emptyList();
  }

  @Nullable
  private PyClassType getMetaclassType() {
    if (myClass == null) {
      return null;
    }
    final PyTargetExpression metaClassAttribute = myClass.findClassAttribute(PyNames.METACLASS, true);
    if (metaClassAttribute != null) {
      final PyExpression metaclass = metaClassAttribute.findAssignedValue();
      if (metaclass instanceof PyReferenceExpression) {
        final QualifiedResolveResult result = ((PyReferenceExpression)metaclass).followAssignmentsChain(PyResolveContext.noImplicits());
        if (result.getElement() instanceof PyClass) {
          return new PyClassType((PyClass) result.getElement(), false);
        }
      }
    }
    return PyBuiltinCache.getInstance(myClass).getObjectType("type");
  }

  @Override
  public PyType getCallType() {
    if (isDefinition()) {
      return new PyClassType(getPyClass(), false);
    }
    return null;
  }

  @Nullable
  private static PsiElement resolveClassMember(PyClassType aClass, String name, @Nullable PyExpression location) {
    PsiElement result = resolveInner(aClass.getPyClass(), name, location);
    if (result != null) {
      return result;
    }
    for (PyClassMembersProvider provider : Extensions.getExtensions(PyClassMembersProvider.EP_NAME)) {
      final PsiElement resolveResult = provider.resolveMember(aClass, name);
      if (resolveResult != null) return resolveResult;
    }

    return null;
  }

  @Nullable
  private static PsiElement resolveInner(PyClass aClass, String name, @Nullable PyExpression location) {
    ResolveProcessor processor = new ResolveProcessor(name);
    ((PyClassImpl)aClass).processDeclarations(processor, location); // our members are strictly within us.
    final PsiElement resolveResult = processor.getResult();
    //final PsiElement resolveResult = PyResolveUtil.treeWalkUp(new PyResolveUtil.ResolveProcessor(name), myClass, null, null);
    if (resolveResult != null && resolveResult != aClass) {
      return resolveResult;
    }
    return null;
  }

  private static Key<Set<PyClassType>> CTX_VISITED = Key.create("PyClassType.Visited");
  public static Key<Boolean> CTX_SUPPRESS_PARENTHESES = Key.create("PyFunction.SuppressParentheses");

  public Object[] getCompletionVariants(String prefix, PyExpression location, ProcessingContext context) {
    if (myClass == null) {
      return ArrayUtil.EMPTY_OBJECT_ARRAY;
    }
    Set<PyClassType> visited = context.get(CTX_VISITED);
    if (visited == null) {
      visited = new HashSet<PyClassType>();
      context.put(CTX_VISITED, visited);
    }
    if (visited.contains(this)) {
      return ArrayUtil.EMPTY_OBJECT_ARRAY;
    }
    visited.add(this);
    Set<String> namesAlready = context.get(CTX_NAMES);
    if (namesAlready == null) {
      namesAlready = new HashSet<String>();
    }
    List<Object> ret = new ArrayList<Object>();
    // from providers
    for (PyClassMembersProvider provider : Extensions.getExtensions(PyClassMembersProvider.EP_NAME)) {
      for (PyDynamicMember member : provider.getMembers(this)) {
        final String name = member.getName();
        ret.add(LookupElementBuilder.create(name).withIcon(member.getIcon()).withTypeText(member.getShortType()));
      }
    }

    boolean suppressParentheses = context.get(CTX_SUPPRESS_PARENTHESES) != null;
    addOwnClassMembers(location, namesAlready, suppressParentheses, ret);

    addInheritedMembers(prefix, location, context, ret);

    if (!myClass.isNewStyleClass()) {
      final PyBuiltinCache cache = PyBuiltinCache.getInstance(myClass);
      final PyClassType classobjType = cache.getOldstyleClassobjType();
      if (classobjType != null) {
        ret.addAll(Arrays.asList(classobjType.getCompletionVariants(prefix, location, context)));
      }
    }

    if (isDefinition() && myClass.isNewStyleClass()) {
      PyClassType typeType = getMetaclassType();
      if (typeType != null) {
        Collections.addAll(ret, typeType.getCompletionVariants(prefix, location, context));
      }
    }

    return ret.toArray();
  }

  private void addOwnClassMembers(PyExpression expressionHook, Set<String> namesAlready, boolean suppressParentheses, List<Object> ret) {
    if (myClass == null) {
      return;
    }
    PyClass containingClass = PsiTreeUtil.getParentOfType(expressionHook, PyClass.class);
    if (containingClass != null) {
      containingClass = CompletionUtil.getOriginalElement(containingClass);
    }
    boolean withinOurClass = containingClass == getPyClass() || isInSuperCall(expressionHook);

    final CompletionVariantsProcessor processor = new CompletionVariantsProcessor(
      expressionHook, new PyResolveUtil.FilterNotInstance(myClass), null
    );
    processor.setNotice(myClass.getName());
    if (suppressParentheses) {
      processor.suppressParentheses();
    }
    ((PyClassImpl)myClass).processClassLevelDeclarations(processor);

    List<String> slots = myClass.isNewStyleClass() ? myClass.getSlots() : null;
    if (slots != null) {
      processor.setAllowedNames(slots);
    }
    ((PyClassImpl)myClass).processInstanceLevelDeclarations(processor, expressionHook);

    for (LookupElement le : processor.getResultList()) {
      String name = le.getLookupString();
      if (namesAlready.contains(name)) continue;
      if (!withinOurClass && isClassPrivate(name)) continue;
      namesAlready.add(name);
      ret.add(le);
    }
    if (slots != null) {
      for (String name : slots) {
        if (!namesAlready.contains(name)) {
          ret.add(LookupElementBuilder.create(name));
        }
      }
    }
  }

  private static boolean isInSuperCall(PyExpression hook) {
    if (hook instanceof PyReferenceExpression) {
      final PyExpression qualifier = ((PyReferenceExpression)hook).getQualifier();
      return qualifier instanceof PyCallExpression && ((PyCallExpression) qualifier).isCalleeText(PyNames.SUPER);
    }
    return false;
  }

  private void addInheritedMembers(String name, PyExpression expressionHook, ProcessingContext context, List<Object> ret) {
    if (myClass == null) {
      return;
    }
    for (PyClass ancestor : myClass.getSuperClasses()) {
      Object[] ancestry = (new PyClassType(ancestor, myIsDefinition)).getCompletionVariants(name, expressionHook, context);
      for (Object ob : ancestry) {
        if (!isClassPrivate(ob.toString())) ret.add(ob);
      }
      ContainerUtil.addAll(ret, ancestry);
    }
  }

  private static boolean isClassPrivate(String lookup_string) {
    return lookup_string.startsWith("__") && !lookup_string.endsWith("__");
  }

  public String getName() {
    PyClass cls = getPyClass();
    if (cls != null) {
      return cls.getName();
    }
    else {
      return null;
    }
  }

  @Override
  public boolean isBuiltin(TypeEvalContext context) {
    return PyBuiltinCache.getInstance(myClass).hasInBuiltins(myClass);
  }

  @Override
  public void assertValid(String message) {
    if (myClass != null && !myClass.isValid()) {
      throw new PsiInvalidElementAccessException(myClass, myClass.getClass().toString() + ": " + message);
    }
  }

  @NotNull
  public Set<String> getPossibleInstanceMembers() {
    Set<String> ret = new HashSet<String>();
    /*
    if (myClass != null) {
      PyClassType otype = PyBuiltinCache.getInstance(myClass.getProject()).getObjectType();
      ret.addAll(otype.getPossibleInstanceMembers());
    }
    */
    // TODO: add our own ideas here, e.g. from methods other than constructor
    return Collections.unmodifiableSet(ret);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    PyClassType classType = (PyClassType)o;

    if (myIsDefinition != classType.myIsDefinition) return false;
    if (myClass != null ? !myClass.equals(classType.myClass) : classType.myClass != null) return false;

    return true;
  }

  @Override
  public int hashCode() {
    int result = myClass != null ? myClass.hashCode() : 0;
    result = 31 * result + (myIsDefinition ? 1 : 0);
    return result;
  }

  public static boolean is(@NotNull String qName, PyType type) {
    if (type instanceof PyClassType) {
      return qName.equals(((PyClassType)type).getClassQName());
    }
    return false;
  }

  @Override
  public String toString() {
    return (isValid() ? "" : "[INVALID] ") + "PyClassType: " + getClassQName();
  }

  public boolean isValid() {
    return myClass == null || myClass.isValid();
  }

  public static PyClassType fromClassName(String typeName, Project project) {
    return new PyClassType(project, typeName, false);
  }
}
