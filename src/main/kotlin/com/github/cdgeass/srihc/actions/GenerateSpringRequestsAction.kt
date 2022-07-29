package com.github.cdgeass.srihc.actions

import com.intellij.httpClient.http.request.HttpRequestFileType
import com.intellij.httpClient.http.request.HttpRequestVariableSubstitutor
import com.intellij.httpClient.http.request.psi.HttpRequestBlock
import com.intellij.httpClient.http.request.psi.HttpRequestMessagesGroup
import com.intellij.httpClient.http.request.psi.codeStyle.HttpRequestGroupBlock
import com.intellij.microservices.url.UrlPath
import com.intellij.microservices.url.UrlResolveRequest
import com.intellij.microservices.url.UrlResolverManager
import com.intellij.microservices.url.references.UrlPathReference
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.search.searches.ReferenceSearcher
import com.intellij.psi.search.searches.ReferencesSearch
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

}
