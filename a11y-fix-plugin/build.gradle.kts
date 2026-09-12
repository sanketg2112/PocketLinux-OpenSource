plugins {
    `kotlin-dsl`
}

gradlePlugin {
    plugins {
        create("a11yFix") {
            id = "com.sg.linuxgo.a11y-fix"
            implementationClass = "com.sg.linuxgo.build.A11yFixPlugin"
        }
    }
}

dependencies {
    compileOnly("com.android.tools.build:gradle:9.3.0")
    implementation("org.ow2.asm:asm:9.7.1")
    implementation("org.ow2.asm:asm-commons:9.7.1")
}
