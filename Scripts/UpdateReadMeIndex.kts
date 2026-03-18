import java.io.File
import kotlin.system.exitProcess

val repoUrl = "https://github.com/Exodia-Pig/PigBrain"
val docsDir = File("docs")
val readmeFile = File("README.md")


fun generateReadme() {
    val content = StringBuilder()
    generateHeaderMessage(content)
    generateMainMessage(content)
    overrideReadMeFile(content)
}

fun generateHeaderMessage(content: StringBuilder) {
    content.appendWithLineBreak("# PigsBrain")
    content.appendWithLineBreak("🐗🚨🐗🚨🐗🚨🐗🚨🐗🚨🐗🚨🐗🚨🐗🚨🐗🚨🐗🚨🐗🚨🐗🚨🐗🚨🐗\n")
    content.appendWithLineBreak("블로그가 글을 짤막하게 쓰기는 안좋은거 같아서 파일 형태로 사용하는 지식 저장소\n")
    content.appendWithLineBreak("[구 블로그 주소](https://mccoy-devloper.tistory.com/) 한번쓰면 수정을 안해서 똥글 투성이\n")
    content.appendWithLineBreak("🐗🚨🐗🚨🐗🚨🐗🚨🐗🚨🐗🚨🐗🚨🐗🚨🐗🚨🐗🚨🐗🚨🐗🚨🐗🚨🐗")
    insertSectionDivider(content)
}

fun generateMainMessage(content: StringBuilder) {
    val folderList = docsDir.listFiles() ?: run {
        println("폴더가 아무것도 없는뎁쇼?🐗")
        exitProcess(0)
    }

    folderList.asSequence().filter { it.isDirectory }.sortedBy { it.name }.forEach { folder ->
        content.appendWithLineBreak("## ${folder.name}")
        appendFolderContent(content, folder, true)
        insertSectionDivider(content)
    }
}

fun appendFolderContent(content: StringBuilder, folder: File, isFirstCall:Boolean) {
    val items = folder.listFiles()?.sortedBy { it.name }
    items?.forEach { item ->
        if (item.isDirectory) {
            content.appendWithLineBreak("### ${item.name}")
            appendFolderContent(content, item, false)
        } else if (item.isFile) {
            // 내부 폴더와 파일 사이 구획을 나누기 위한 방법
            if(isFirstCall) content.appendWithLineBreak("### ${folder.name} 폴더없는 친구들")
            val filePath = "tree/main/docs/${folder.relativeTo(docsDir).path}/${item.name}"
            val fileUrl = "$repoUrl/$filePath"
            content.appendWithLineBreak("- [${item.name}]($fileUrl)")
        }
    }
}

fun overrideReadMeFile(content: StringBuilder) {
    readmeFile.writeText(content.toString())
    println("리드미 업데이트 완료 히히히")
}

fun insertSectionDivider(content: StringBuilder) {
    content.append("\n\n\n")
}

fun StringBuilder.appendWithLineBreak(value: String) {
    append(value + "\n")
}

// 최종 실행
generateReadme()
