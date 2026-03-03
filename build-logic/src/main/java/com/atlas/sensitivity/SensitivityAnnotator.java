package com.atlas.sensitivity;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

public class SensitivityAnnotator {
    private static final String PATH_GENERATED_PROTO_BUILD = "build/generated/sources/proto/main";
    private static final String FQCN_SENSITIVE = "com.atlas.utility.annotation.Sensitive";
    private static final String FQCN_CHECKER_SENSITIVE = "com.atlas.checker.sensitivity.qual.Sensitive";

    public static void main(String[] args) {
        scanAndAnnotateFiles(PATH_GENERATED_PROTO_BUILD);
    }

    public static void scanAndAnnotateFiles(String directoryPath) {
        File directory = new File(directoryPath);
        if (!directory.exists() || !directory.isDirectory()) {
            return;
        }

        List<File> javaFiles = getJavaFiles(directory);
        javaFiles.parallelStream().forEach(SensitivityAnnotator::processJavaFile);
    }

    private static List<File> getJavaFiles(File directory) {
        return Arrays.stream(Objects.requireNonNull(directory.listFiles()))
            .flatMap(file -> file.isDirectory() ? getJavaFiles(file).stream() : java.util.stream.Stream.of(file))
            .filter(file -> {
                try {
                    return file.getName().endsWith(".java") && Files.readString(file.toPath()).contains("(.sensitive) = true");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).toList();
    }

    private static void processJavaFile(File file) {
        try {
            String content = new String(Files.readAllBytes(file.toPath()));

            JavaParser parser = new JavaParser();
            ParseResult<CompilationUnit> result = parser.parse(content);

            if (result.isSuccessful()) {
                Optional<CompilationUnit> optionalCU = result.getResult();
                if (optionalCU.isPresent()) {
                    CompilationUnit cu = optionalCU.get();
                    final boolean[] updateFileFlag = {false};
                    cu.accept(new VoidVisitorAdapter<Void>() {
                        @Override
                        public void visit(MethodDeclaration method, Void arg) {
                            super.visit(method, arg);
                            if (!hasSensitiveComment(method)) {
                                return;
                            }

                            // Annotate getter's return type
                            if (isGetterMethod(method) && annotatedGetter(method)) {
                                updateFileFlag[0] = true;
                            }

                            // Annotate setter's parameter
                            if (isSetterMethod(method) && annotatedSetter(method)) {
                                updateFileFlag[0] = true;
                            }
                        }
                    }, null);

                    if (updateFileFlag[0]) {
                        Files.write(file.toPath(), cu.toString().getBytes());
                    }
                }
            }
        } catch (Exception e) {
            Logger.getLogger(SensitivityAnnotator.class.getName()).log(Level.SEVERE, "An exception occurred while adding annotation", e);
        }
    }

    private static boolean annotatedGetter(MethodDeclaration method) {
        if (isMapType(method.getType())) {
            final NodeList<Type> typeArguments = ((ClassOrInterfaceType) method.getType()).getTypeArguments().orElseThrow();
            final ClassOrInterfaceType valueType = (ClassOrInterfaceType) typeArguments.get(1);
            if (!containsSensitiveAnnotation(valueType.getAnnotations())) {
                valueType.addAnnotation(FQCN_CHECKER_SENSITIVE);
                return true;
            }
        } else {
            if (!containsSensitiveAnnotation(method.getAnnotations())) {
                method.addAnnotation(FQCN_SENSITIVE);
                return true;
            }
        }
        return false;
    }

    private static boolean annotatedSetter(MethodDeclaration method) {
        if (method.getParameters().size() == 1) {
            final Parameter parameter = method.getParameter(0);
            if (isMapType(parameter.getType())) {
                // public Builder putAllPii(java.util.Map<java.lang.String, java.lang.String> values)
                final NodeList<Type> typeArguments = ((ClassOrInterfaceType) parameter.getType()).getTypeArguments().orElseThrow();
                final ClassOrInterfaceType valueType = (ClassOrInterfaceType) typeArguments.get(1);
                if (!containsSensitiveAnnotation(valueType.getAnnotations())) {
                    valueType.addAnnotation(FQCN_CHECKER_SENSITIVE);
                    return true;
                }
            } else {
                if (!containsSensitiveAnnotation(parameter.getAnnotations())) {
                    parameter.addAnnotation(FQCN_SENSITIVE);
                    return true;
                }
            }
        } else if (method.getParameters().size() == 2 && method.getParameter(0).getNameAsString().equals("key") && method.getParameter(1)
            .getNameAsString().equals("value")) {
            final Parameter parameter = method.getParameter(1);
            if (!containsSensitiveAnnotation(parameter.getAnnotations())) {
                parameter.addAnnotation(FQCN_SENSITIVE);
                return true;
            }
        }
        return false;
    }

    private static boolean isGetterMethod(MethodDeclaration method) {
        // TODO: Exclude int getListCount() and int getMapCount()
        final String methodName = method.getNameAsString();
        return
            (methodName.startsWith("get") && method.getParameters().isEmpty()) // Getter
            || (methodName.startsWith("get") && method.getParameters().size() == 1 && method.getParameter(0).getTypeAsString()
                .equals("int") && method.getParameter(0).getNameAsString().equals("index")) // Getter for getList(int index)
            || (methodName.startsWith("get") && methodName.endsWith("OrDefault") && method.getParameters().size() == 2
                && method.getParameter(0).getNameAsString().equals("key") && method.getParameter(1).getNameAsString()
                    .equals("defaultValue"))  // Getter for getPiiMapOrDefault(T key, T defaultValue)
            || (methodName.startsWith("get") && methodName.endsWith("OrThrow") && method.getParameters().size() == 1
                && method.getParameter(0).getNameAsString().equals("key")); // Getter for getPiiMapOrThrow(java.lang.String key)
    }

    private static boolean isSetterMethod(MethodDeclaration method) {
        return (method.getTypeAsString().equals("Builder") && (method.getNameAsString().startsWith("set") || method.getNameAsString()
            .startsWith("putAll")) && method.getParameters().size() == 1 || method.getParameters().size() == 2) // Setter for Map
               || (method.getTypeAsString().equals("Builder") && method.getNameAsString().startsWith("addAll")
                   && method.getParameters().size() == 1); // Setter for list TODO: Check if list parameter type is iterable
    }

    private static boolean hasSensitiveComment(MethodDeclaration method) {
        return method.getJavadocComment().map(comment -> comment.toString().contains("(.sensitive) = true")).orElse(false);
    }

    private static boolean containsSensitiveAnnotation(NodeList<AnnotationExpr> annotations) {
        return annotations.stream().anyMatch(
            annotation -> annotation.getNameAsString().equals(FQCN_SENSITIVE) || annotation.getNameAsString().equals(FQCN_CHECKER_SENSITIVE));
    }

    private static boolean isMapType(Type type) {
        if (type instanceof ClassOrInterfaceType paramType && "Map".equals(paramType.getNameAsString())) {
            final Optional<NodeList<Type>> typeArguments = paramType.getTypeArguments();
            if (typeArguments.isPresent()) {
                NodeList<Type> typeArgs = typeArguments.get();
                if (typeArgs.size() == 2) {
                    Type valueType = typeArgs.get(1);
                    return valueType instanceof ClassOrInterfaceType;
                }
            }
        }

        return false;
    }
}
