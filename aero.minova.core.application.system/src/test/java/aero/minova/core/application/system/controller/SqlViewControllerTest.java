package aero.minova.core.application.system.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import aero.minova.core.application.system.domain.Column;
import aero.minova.core.application.system.domain.DataType;
import aero.minova.core.application.system.domain.Table;

@SpringBootTest
class SqlViewControllerTest {

	@Autowired
	SqlViewController testSubject;

	@Disabled
	@Test
	void testIndexViewResultName() {
		// assertThat(testSubject.getIndexView("IndexViewName").getName()).isEqualTo("IndexViewName");
	}

	@Test
	void testPrepareViewString_withStarSelect() {
		Table inputTable = new Table();
		inputTable.setName("vWorkingTimeIndex2");
		assertThat(testSubject.prepareViewString(inputTable, true, 1000))//
				.isEqualTo("select top 1000 * from vWorkingTimeIndex2");
	}

}