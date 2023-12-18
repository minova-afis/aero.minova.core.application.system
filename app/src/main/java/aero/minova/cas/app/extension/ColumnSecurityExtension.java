package aero.minova.cas.app.extension;

import org.springframework.stereotype.Component;

import aero.minova.cas.service.model.ColumnSecurity;
import jakarta.annotation.PostConstruct;

@Component
public class ColumnSecurityExtension extends BaseExtension<ColumnSecurity> {

	@PostConstruct
	public void setup() {
		super.setup(ColumnSecurity.class);
	}
}