package com.svi.channelconnect.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/api/webhooks/messenger", "/webhooks/whatsapp", "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/webhooks/messenger", "/webhooks/whatsapp", "/api/v1/whatsapp/messages").permitAll()
                        // Local/test SMS controllers additionally require the SMS bearer filter.
                        .requestMatchers(HttpMethod.POST, "/api/v1/sms/messages").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/sms/messages/*").permitAll()
                        .requestMatchers(HttpMethod.DELETE, "/api/admin/conversations/*").hasRole("ADMIN")
                        .requestMatchers("/api/admin/demo/**").hasRole("ADMIN")
                        .requestMatchers("/api/admin/sms/**").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .httpBasic(Customizer.withDefaults())
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(AdminProperties properties, PasswordEncoder encoder) {
        return new InMemoryUserDetailsManager(User.withUsername(properties.username())
                .password(encoder.encode(properties.password()))
                .roles("ADMIN")
                .build());
    }
}
