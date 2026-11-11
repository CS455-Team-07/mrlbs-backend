package com.mrlbs.auth;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class AdminAccountProvisioning implements ApplicationRunner {
    private final PasswordService passwords;
    private final Environment environment;

    public AdminAccountProvisioning(PasswordService passwords, Environment environment) {
        this.passwords = passwords;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        passwords.provisionAdmin("lhc@iitk.ac.in", environment.getProperty("MRLBS_LHC_INITIAL_PASSWORD"));
        passwords.provisionAdmin("gpus@iitk.ac.in", environment.getProperty("MRLBS_GPUS_INITIAL_PASSWORD"));
        passwords.provisionAdmin("kdlab@iitk.ac.in", environment.getProperty("MRLBS_KDLAB_INITIAL_PASSWORD"));
    }
}
