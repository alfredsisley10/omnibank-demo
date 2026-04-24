dependencies {
    implementation(project(":shared-domain"))
    implementation(project(":shared-messaging"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-mail")

    testImplementation(project(":shared-testing"))
}
