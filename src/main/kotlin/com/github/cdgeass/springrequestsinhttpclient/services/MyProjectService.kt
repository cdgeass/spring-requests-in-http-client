package com.github.cdgeass.springrequestsinhttpclient.services

import com.intellij.openapi.project.Project
import com.github.cdgeass.springrequestsinhttpclient.MyBundle

class MyProjectService(project: Project) {

    init {
        println(MyBundle.message("projectService", project.name))
    }
}
