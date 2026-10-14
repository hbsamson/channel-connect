package com.svi.channelconnect.dealership.service;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.svi.channelconnect.dealership.model.CampaignConfiguration;
import com.svi.channelconnect.dealership.model.CustomerProfile;

@Component
public class CampaignTemplates {
	private static final Pattern VARIABLE = Pattern.compile("\\{\\{([a-z_]+)}}");
	public Map<String, String> values(CustomerProfile customer, String dealer, String service, String appointment) {
		Map<String, String> values = new HashMap<>();
		values.put("first_name", customer.firstName());
		values.put("vehicle_model", customer.vehicleModel());
		values.put("renewal_due", customer.renewalDue());
		values.put("dealer_name", dealer);
		values.put("service_offering", service);
		values.put("appointment_time", appointment);
		return values;
	}
	public String render(CampaignConfiguration config, String key, String language, Map<String, String> values) {
		String template = "tl".equals(language) && config.taglishScripts() != null ? config.taglishScripts().get(key) : null;
		if (template == null) template = config.scripts().get(key);
		if (template == null || template.isBlank()) throw new IllegalArgumentException("Missing approved script");
		var matcher = VARIABLE.matcher(template);
		StringBuilder result = new StringBuilder();
		while (matcher.find()) {
			String value = values.get(matcher.group(1));
			if (value == null || value.isBlank() || value.contains("{{")) throw new IllegalArgumentException("Missing required script variable: " + matcher.group(1));
			matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(value));
		}
		matcher.appendTail(result);
		if (result.indexOf("{{") >= 0 || result.length() > 4096) throw new IllegalArgumentException("Invalid campaign script");
		return result.toString();
	}
}
