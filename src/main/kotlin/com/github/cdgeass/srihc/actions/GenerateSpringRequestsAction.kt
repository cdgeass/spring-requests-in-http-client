package com.github.cdgeass.srihc.actions

import com.github.cdgeass.srihc.actions.AnnotationType.RequestBody
import com.github.cdgeass.srihc.actions.AnnotationType.RequestParam
import com.intellij.httpClient.http.request.HttpRequestFileType
import com.intellij.httpClient.http.request.HttpRequestHeaderFields
import com.intellij.httpClient.http.request.HttpRequestPsiFile
import com.intellij.httpClient.http.request.HttpRequestVariableSubstitutor
import com.intellij.httpClient.http.request.psi.HttpRequest
import com.intellij.httpClient.http.request.psi.HttpRequestBlock
import com.intellij.httpClient.http.request.psi.HttpRequestMessagesGroup
import com.intellij.microservices.url.UrlPath
import com.intellij.microservices.url.UrlResolveRequest
import com.intellij.microservices.url.UrlResolverManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiUtilCore

enum class AnnotationType(val qualifiedName: String) {
    RequestParam("org.springframework.web.bind.annotation.RequestParam"), RequestBody("org.springframework.web.bind.annotation.RequestBody");

    companion object {
        fun valueOf(qualifiedName: String?): AnnotationType? {
            return AnnotationType.values().firstOrNull { it.qualifiedName == qualifiedName }
        }
    }
}

class GenerateSpringRequestsAction : AnAction() {

    override fun update(event: AnActionEvent) {
        super.update(event)

        event.presentation.isEnabled = isEnable(event)
    }

    /**
     * 判断生成选项是否显示
     */
    private fun isEnable(event: AnActionEvent): Boolean {
        // 获取编辑器
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return false

        // 仅在 .http 文件中生成
        val psiFile = event.getData(CommonDataKeys.PSI_FILE) ?: return false
        if (psiFile.fileType != HttpRequestFileType.INSTANCE) {
            return false
        }

        /*
        当前位置为空白, 前一个元素为请求体, 且后一个元素不为消息体时生成
        */
        val element = PsiUtilCore.getElementAtOffset(psiFile, editor.caretModel.offset)
        return element is PsiWhiteSpace && element.prevSibling is HttpRequestBlock && element.nextSibling !is HttpRequestMessagesGroup
    }

    override fun actionPerformed(event: AnActionEvent) {
        // 获取 document
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return
        val psiFile = event.getData(CommonDataKeys.PSI_FILE) ?: return
        val document = editor.document
        if (!document.isWritable) {
            return
        }
        document.fireReadOnlyModificationAttempt()

        // 获取 endpoint 解析参数
        val request = getHttpRequest(event) ?: return
        val method = searchEndpoint(request) ?: return
        val params = getRequestParams(method)

        // 通过 CommandProcessor 执行修改可撤销
        val processor = CommandProcessor.getInstance()
        ApplicationManager.getApplication().runWriteAction {
            processor.executeCommand(event.project, {

            }, "io.github.cdgeass.GenerateSpringRequests", document)
        }

        return
    }

    /**
     * 获取对应请求体
     */
    private fun getHttpRequest(event: AnActionEvent): HttpRequest? {
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return null

        val psiFile = event.getData(CommonDataKeys.PSI_FILE) ?: return null

        val element = PsiUtilCore.getElementAtOffset(psiFile, editor.caretModel.offset)
        return element.prevSibling.let {
            if (it !is HttpRequestBlock) {
                return@let null
            }
            it
        }?.request
    }

    /**
     * 根据路径查找对应的方法
     */
    @Suppress("UnstableApiUsage")
    private fun searchEndpoint(request: HttpRequest): PsiMethod? {
        // 获取请求路径
        val path =
            request.requestTarget?.pathAbsolute?.getHttpPath(HttpRequestVariableSubstitutor.empty()) ?: return null

        // 获取 endpoint
        val urlResolverManager = UrlResolverManager.getInstance(request.project)
        val endPoint =
            urlResolverManager.getVariants(UrlResolveRequest(null, null, UrlPath.fromExactString(path))).firstOrNull()
                ?: return null
        // 解析到方法
        val psiMethod = endPoint.resolveToPsiElement() ?: return null
        if (psiMethod !is PsiMethod) {
            return null
        }
        return psiMethod
    }

    /**
     * 获取接口方法的参数
     * - 标注了 @RequestBody 的参数
     */
    private fun getRequestParams(method: PsiMethod): List<Pair<AnnotationType, PsiParameter>> {
        return method.parameterList.parameters.filter { isSpringAnnotationParam(it) }.mapNotNull {
            val annotationType = it.annotations.first { annotation -> isSpringAnnotation(annotation.qualifiedName) }
                .let { annotation -> AnnotationType.valueOf(annotation.qualifiedName) } ?: return@mapNotNull null
            Pair(annotationType, it)
        }
    }

    /**
     * 判断参数是否是 spring 注解的参数
     */
    private fun isSpringAnnotationParam(param: PsiParameter): Boolean {
        return param.annotations.any { isSpringAnnotation(it.qualifiedName) }
    }

    /**
     * 根据限定名判断是否是 spring 注解
     */
    private fun isSpringAnnotation(qualifiedName: String?): Boolean {
        if (qualifiedName == null) {
            return false
        }
        return qualifiedName.startsWith("org.springframework.web.bind.annotation")
    }

    /**
     * 生成匿名的 .http 文件, 取 PsiElement 进行插入
     */
    private fun createDummyFile(params: List<Pair<AnnotationType, PsiParameter>>): HttpRequestPsiFile {
        val headerFields = mutableListOf<String>()

        var requestBody = ""
        params.forEach { p ->
            val annotationType = p.first
            val param = p.second

            if (annotationType == RequestParam) {
                if (param.type is PsiClassType) {
                    if ((param.type as PsiClassType).resolve()?.name == "MultipartFile") {
                        headerFields.add("${HttpRequestHeaderFields.CONTENT_TYPE}: multipart/form-data; boundary=boundary")
                    }
                }
            }
            if (annotationType == RequestBody) {
                val headerField = "${HttpRequestHeaderFields.CONTENT_TYPE}: application/json"
                if (!headerFields.contains(headerField)) {
                    headerFields.add(headerField)
                }

                if (param.type is PsiClassType) {
                    val clazz = (param.type as PsiClassType).resolve()
                    clazz?.apply {
                        requestBody = "{"
                        this.fields.forEach { field ->
                            requestBody += "\n\t\"${field.name}\": ,"
                        }
                        requestBody += "\n}"
                    }
                }
            }
        }

        val dummyText = """
            ###
            POST http://0.0.0.0
            
        """.trimIndent()
    }

}