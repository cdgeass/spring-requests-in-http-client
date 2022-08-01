package com.github.cdgeass.srihc.actions

import com.intellij.httpClient.http.request.HttpRequestFileType
import com.intellij.httpClient.http.request.HttpRequestVariableSubstitutor
import com.intellij.httpClient.http.request.psi.HttpRequestBlock
import com.intellij.httpClient.http.request.psi.HttpRequestMessagesGroup
import com.intellij.microservices.url.UrlPath
import com.intellij.microservices.url.UrlResolveRequest
import com.intellij.microservices.url.UrlResolverManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.CommandProcessor
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiUtilCore

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
        val requestBlock = getHttpRequestBlock(event) ?: return
        val method = searchEndpoint(requestBlock) ?: return

        val param = getRequestParam(method) ?: return
        insertRequestMessage(event, param)

        return
    }

    /**
     * 获取对应请求体
     */
    private fun getHttpRequestBlock(event: AnActionEvent): HttpRequestBlock? {
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return null

        val psiFile = event.getData(CommonDataKeys.PSI_FILE) ?: return null

        val element = PsiUtilCore.getElementAtOffset(psiFile, editor.caretModel.offset)
        return element.prevSibling.let {
            if (it !is HttpRequestBlock) {
                return@let null
            }
            it
        }
    }

    /**
     * 根据路径查找对应的方法
     */
    @Suppress("UnstableApiUsage")
    private fun searchEndpoint(httpRequestBlock: HttpRequestBlock): PsiMethod? {
        // 获取请求路径
        val path =
            httpRequestBlock.request.requestTarget?.pathAbsolute?.getHttpPath(HttpRequestVariableSubstitutor.empty())
                ?: return null

        // 获取 endpoint
        val urlResolverManager = UrlResolverManager.getInstance(httpRequestBlock.project)
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
    private fun getRequestParam(method: PsiMethod): PsiParameter? {
        return method.parameterList.parameters.filter { !isInternalParam(it) }.firstOrNull { it ->
                it.annotations.any { it.hasQualifiedName("org.springframework.web.bind.annotation.RequestBody") }
            }
    }

    /**
     * 判断参数是否是 spring 的内置参数
     */
    private fun isInternalParam(param: PsiParameter): Boolean {
        return false
    }

    /**
     * 生成 .http 文件中插入请求体
     */
    private fun insertRequestMessage(event: AnActionEvent, param: PsiParameter) {
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return
        val document = editor.document
        if (!document.isWritable) {
            return
        }
        document.fireReadOnlyModificationAttempt()
        val offset = editor.caretModel.offset

        val process = Runnable {
            document.insertString(offset, "{}")
        }
        // 通过 CommandProcessor 执行修改可撤销
        val processor = CommandProcessor.getInstance()
        processor.executeCommand(event.project, process, "io.github.cdgeass.GenerateSpringRequests", document)
    }

}
