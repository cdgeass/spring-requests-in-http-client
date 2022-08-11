package com.github.cdgeass.srihc.actions

import com.github.cdgeass.srihc.actions.AnnotationType.*
import com.intellij.httpClient.http.request.HttpRequestFileType
import com.intellij.httpClient.http.request.HttpRequestHeaderFields
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
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiUtilCore
import com.intellij.refactoring.suggested.endOffset

enum class AnnotationType(val qualifiedName: String) {
    RequestParam("org.springframework.web.bind.annotation.RequestParam"),
    RequestBody("org.springframework.web.bind.annotation.RequestBody");

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
                processRequestBody(event, document, request, params)
                processRequestParam(event, document, request, params)
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
        val path = request.requestTarget?.pathAbsolute?.getHttpPath(HttpRequestVariableSubstitutor.empty())
                ?: return null

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
        return method.parameterList.parameters
            .filter { isSpringAnnotationParam(it) }
            .mapNotNull {
                val annotationType = it.annotations
                    .first { annotation -> isSpringAnnotation(annotation.qualifiedName) }
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
     * 处理 requestBody
     */
    private fun processRequestBody(
        event: AnActionEvent,
        document: Document,
        request: HttpRequest,
        params: List<Pair<AnnotationType, PsiParameter>>
    ) {
        val paramPair = params.firstOrNull { it.first == RequestBody } ?: return
        val param = paramPair.second

        var jsonStr = "{\n}"
        val type = param.type
        if (type is PsiClassType) {
            type.resolve()?.apply {
                val fields = this.allFields
                jsonStr = "{\n"
                fields.forEachIndexed { i, field ->
                    jsonStr += "\t\"${field.name}\": ${if (i == fields.size - 1) "" else ","}\n"
                }
                jsonStr += "}"
            }
        }

        computeHeader(event, document, request, HttpRequestHeaderFields.CONTENT_TYPE, "application/json")
        insertRequestMessage(event, document, null, jsonStr)
    }

    private fun processRequestParam(
        event: AnActionEvent,
        document: Document,
        request: HttpRequest,
        params: List<Pair<AnnotationType, PsiParameter>>
    ) {
        val multipartParamPair = params.firstOrNull {
            val type = it.second.type
            if (type is PsiClassType) {
                val clazz = type.resolve()
                if (clazz != null) {
                    if (clazz.name == "MultipartFile" && it.first == RequestParam) {
                        return@firstOrNull true
                    }
                }
            }
            false
        }

        // 拼装 param
        val paramString = params.filter { it != multipartParamPair && it.first == RequestParam }
            .joinToString(prefix = "?", separator = "&") {
                val param = it.second
                val paramName =
                    param.annotations.firstOrNull { annotation -> annotation.qualifiedName == it.first.qualifiedName }
                        ?.findAttributeValue("value")?.text?.removePrefix("\"")?.removeSuffix("\"")
                "${paramName ?: param.name}="
            }
        insertRequestMessage(event, document, request.requestTarget!!.endOffset, paramString)

        // 插入 form
        if (multipartParamPair != null) {
            computeHeader(event, document, request, HttpRequestHeaderFields.CONTENT_TYPE, "multipart/form-data")

            val multipartParam = multipartParamPair.second
            val type = multipartParam.type
            if (type is PsiClassType) {
                if (type.className == "MultipartFile") {
                    val annotation =
                        multipartParam.annotations.first { it.qualifiedName == multipartParamPair.first.qualifiedName }
                    val fileName =
                        annotation.parameterList.attributes.firstOrNull { it.name == "value" }?.text ?: ""
                    val formString = """
                    --boundary
                    Content-Disposition: form-data; name="$fileName"; filename=""
                    <
                    """.trimIndent()
                    insertRequestMessage(event, document, null, formString)
                }
            }
        }
    }

    /**
     * 判断 header 是否存在, 不存在则插入
     */
    private fun computeHeader(
        event: AnActionEvent,
        document: Document,
        request: HttpRequest,
        headerFieldName: String,
        headerFieldValue: String
    ) {
        val headerFieldList = request.headerFieldList
        headerFieldList.forEach {
            if (it.name == headerFieldName && it.headerFieldValue?.text?.contains(
                    headerFieldValue,
                    true
                ) == true
            ) {
                return
            }
        }

        if (headerFieldList.isNotEmpty()) {
            insertRequestMessage(
                event,
                document,
                headerFieldList[headerFieldList.size - 1].endOffset,
                "$headerFieldName: $headerFieldValue"
            )
        } else {
            // TODO 插入位置
            insertRequestMessage(event, document, request.requestTarget!!.endOffset, "\n$headerFieldName: $headerFieldValue")
        }
    }

    /**
     * 生成 .http 文件中插入请求体
     */
    private fun insertRequestMessage(
        event: AnActionEvent,
        document: Document,
        offset: Int?,
        str: String
    ) {
        val editor = event.getData(CommonDataKeys.EDITOR)!!
        document.insertString(offset ?: editor.caretModel.offset, str)
    }
}
