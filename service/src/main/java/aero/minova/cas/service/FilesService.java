package aero.minova.cas.service;

import static java.nio.file.Files.isDirectory;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.annotation.PostConstruct;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.Marshaller;
import javax.xml.namespace.QName;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import aero.minova.cas.CustomLogger;
import aero.minova.cas.api.domain.Column;
import aero.minova.cas.api.domain.DataType;
import aero.minova.cas.api.domain.Row;
import aero.minova.cas.api.domain.Table;
import aero.minova.cas.controller.SqlViewController;
import aero.minova.cas.service.mdi.Main;
import aero.minova.cas.service.mdi.Main.Action;
import aero.minova.cas.service.mdi.Main.Entry;
import aero.minova.cas.service.mdi.Main.Menu;

@Service
public class FilesService {

	@Value("${aero_minova_core_application_root_path:.}")
	private String rootPath;

	@Value("${files.permission.check:false}")
	boolean permissionCheck;

	@Autowired
	SecurityService securityUtils;

	@Autowired
	SqlViewController viewController;

	@Autowired
	public CustomLogger customLogger;
	private Path systemFolder;
	private Path internalFolder;
	private Path logsFolder;
	private Path zipsFolder;
	private Path md5Folder;

	public FilesService() {}

	public FilesService(String rootPath) {
		this.rootPath = rootPath;
	}

	/**
	 * Initialisiert alle nötigen Ordner. Mit {@link Path#toAbsolutePath()} und {@link Path#normalize} werden die Pfade so eindeutig wie möglich.
	 */
	@PostConstruct
	public void setUp() {
		if (rootPath == null || rootPath.isEmpty()) {
			rootPath = Paths.get(".").toAbsolutePath().normalize().toString();
		}
		systemFolder = Paths.get(rootPath).toAbsolutePath().normalize();
		internalFolder = systemFolder.resolve("Internal").toAbsolutePath().normalize();
		logsFolder = internalFolder.resolve("UserLogs").toAbsolutePath().normalize();
		md5Folder = internalFolder.resolve("MD5").toAbsolutePath().normalize();
		zipsFolder = internalFolder.resolve("Zips").toAbsolutePath().normalize();
		if (!isDirectory(systemFolder)) {
			customLogger.logFiles("msg.SystemFolder %" + systemFolder);
		}
		if (!isDirectory(internalFolder)) {
			customLogger.logFiles("msg.InternalFolder %" + internalFolder);
		}
		if (!isDirectory(logsFolder)) {
			customLogger.logFiles("msg.LogsFolder %" + logsFolder);
		}
		if (md5Folder.toFile().mkdirs()) {
			customLogger.logFiles("Creating directory " + md5Folder);
		}
		if (zipsFolder.toFile().mkdirs()) {
			customLogger.logFiles("Creating directory " + zipsFolder);
		}
	}

	/**
	 * Gibt den Pfad zum Systems-Ordner zurück.
	 * 
	 * @return Pfad zum System-Ordner.
	 */
	public Path getSystemFolder() {
		return systemFolder;
	}

	/**
	 * Gibt den Pfad zum UserLogs-Ordner zurück.
	 * 
	 * @return Pfad zum UserLogs-Ordner.
	 */
	public Path getLogsFolder() {
		return logsFolder;
	}

	/**
	 * Gibt den Pfad zum MD5-Ordner zurück.
	 * 
	 * @return Pfad zum MD5-Ordner.
	 */
	public Path getMd5Folder() {
		return md5Folder;
	}

	/**
	 * Gibt den Pfad zum Zips-Ordner zurück.
	 * 
	 * @return Pfad zum Zip-Ordner.
	 */
	public Path getZipsFolder() {
		return zipsFolder;
	}

	/**
	 * Diese Methode erzeugt eine Liste aller vorhandenen Files in einem Directory. Falls sich noch weitere Directories in diesem befinden, wird deren Inhalt
	 * ebenfalls aufgelistet
	 * 
	 * @param dir
	 *            das zu durchsuchende Directory
	 * @return eine Liste an allen Files in dem übergebenen Directory
	 * @throws FileNotFoundException
	 *             Falls das Directory nicht existiert oder der übergebene Pfad nicht auf ein Directory zeigt.
	 */
	public List<Path> populateFilesList(Path dir) throws FileNotFoundException {
		List<Path> filesListInDir = new ArrayList<>();
		File[] files = dir.toFile().listFiles();
		if (files == null) {
			throw new FileNotFoundException("Cannot access sub folder: " + dir);
		}
		for (File file : files) {
			filesListInDir.add(Paths.get(file.getAbsolutePath()));
			if (file.isDirectory()) {
				filesListInDir.addAll(populateFilesList(file.toPath()));
			}
		}
		return filesListInDir;
	}

	/**
	 * Überprüft, ob die angeforderte Datei existiert und ob der Pfad dorthin innerhalb des dedizierten Dateisystems liegt.
	 * 
	 * @param path
	 *            Pfad zur gewünschten Datei.
	 * @throws Exception
	 *             RuntimeException, falls User nicht erforderliche Privilegien besitzt, IllegalAccessException, falls der Pfad nicht in das abgegrenzte
	 *             Dateisystem zeigt, NoSuchFileException, falls gewünschte Datei nicht existiert.
	 */
	public Path checkLegalPath(Path path) throws Exception {
		if (permissionCheck) {
			List<Row> privileges = securityUtils.getPrivilegePermissions("files/read:" + path);
			if (privileges.isEmpty()) {
				throw new RuntimeException("msg.PrivilegeError %" + "files/read:" + path);
			}
		}
		Path inputPath = getSystemFolder().resolve(path).toAbsolutePath().normalize();
		File f = inputPath.toFile();
		if (!inputPath.startsWith(getSystemFolder())) {
			throw new IllegalAccessException("msg.PathError %" + path + " %" + inputPath);
		}
		if (!f.exists()) {
			throw new NoSuchFileException("msg.FileError %" + path);
		}
		return inputPath;
	}

	/**
	 * Methode zum Zippen einer Datei.
	 * 
	 * @param source
	 *            String, Teil des ursprünglichen Pfades, welcher abgeschnitten werden muss.
	 * @param zipFile
	 *            File, gewünschtes finales Zip-File.
	 * @param fileList
	 *            List&lt;Path&gt;, Pfade zu Dateien, welche gezipped werden sollen.
	 * @throws RuntimeException
	 *             Falls eine Datei nicht gezipped werden kann, zum Beispiel aufgrund eines falschen Pfades.
	 * @throws FileNotFoundException
	 */
	public void zip(String source, File zipFile, List<Path> fileList) throws Exception {
		ZipEntry ze = null;
		// Jede Datei wird einzeln zu dem ZIP hinzugefügt.
		FileOutputStream fos = new FileOutputStream(zipFile);
		try (ZipOutputStream zos = new ZipOutputStream(fos);) {

			for (Path filePath : fileList) {

				// noch mehr zipps in einer zip sind sinnlos
				if (filePath.toFile().isFile() && (!filePath.toString().contains("zip"))) {
					ze = new ZipEntry(filePath.toString().substring(source.length() + 1, filePath.toString().length()).replace('\\', '/'));

					// CreationTime der Zip und Änderungs-Zeitpunkt der Zip auf diese festen
					// Zeitpunkte setzen, da sich sonst jedes Mal der md5 Wert ändert,
					// wenn die Zip erstellt wird.
					ze.setCreationTime(FileTime.from(Instant.EPOCH));
					ze.setTime(0);
					zos.putNextEntry(ze);

					// Jeder Eintrag wird nacheinander in die ZIP Datei geschrieben mithilfe eines
					// Buffers.
					FileInputStream fis = new FileInputStream(filePath.toFile());

					int len;
					byte[] buffer = new byte[1024];

					try (BufferedInputStream entryStream = new BufferedInputStream(fis, 2048)) {
						while ((len = entryStream.read(buffer, 0, 1024)) != -1) {
							zos.write(buffer, 0, len);
						}
					} finally {
						zos.closeEntry();
						fis.close();
					}
				}
			}
		} catch (Exception e) {
			if (ze != null) {
				customLogger.logFiles("Error while zipping file " + ze.getName());
				throw new RuntimeException("msg.ZipError %" + ze.getName());
			} else {
				// Landet nur hier, wenn es nicht mal bis in das erste if geschafft hat.
				customLogger.logFiles("Error while accessing file path for file to zip.");
				throw new RuntimeException("Error while accessing file path " + source + " for file to zip.", e);
			}
		} finally {
			fos.close();
		}
	}

	/**
	 * Methode zum Entpacken einer Datei.
	 * 
	 * @param fileZip
	 *            File, die gepackte Datei.
	 * @param destDirName
	 *            Path, Pfad im Dateisystem, an welchem der Inhalt des Zips gespeichert werden soll.
	 * @throws IOException
	 *             Falls das Directory nicht existiert oder kein Directory ist oder falls die Datei nicht entpackt werden kann.
	 */
	public void unzipFile(File fileZip, Path destDirName) throws IOException {
		byte[] buffer = new byte[1024];
		FileInputStream fis;
		try {
			fis = new FileInputStream(fileZip);
			ZipInputStream zis = new ZipInputStream(fis);
			ZipEntry ze = zis.getNextEntry();
			while (ze != null) {
				String zippedFileEntry = ze.getName();
				if (zippedFileEntry.startsWith(File.separator)) {
					zippedFileEntry = zippedFileEntry.substring(1);
				}
				File newFile = destDirName.resolve(zippedFileEntry).toFile();
				// create directories for sub directories in zip
				new File(newFile.getParent()).mkdirs();
				FileOutputStream fos = new FileOutputStream(newFile);
				int len;
				while ((len = zis.read(buffer)) > 0) {
					fos.write(buffer, 0, len);
				}
				fos.close();
				zis.closeEntry();
				ze = zis.getNextEntry();
			}
			zis.closeEntry();
			zis.close();
			fis.close();
		} catch (IOException e) {
			customLogger.logFiles("Error while unzipping file " + fileZip + " into directory " + destDirName);
			throw new RuntimeException("msg.UnZipError %" + fileZip + " %" + destDirName, e);

		}
	}

	public byte[] readMDI() {

		String user = SecurityContextHolder.getContext().getAuthentication().getName();

		Table mdi = new Table();
		mdi.setName("xtcasMdi");

		mdi.addColumn(new Column("ID", DataType.STRING));
		mdi.addColumn(new Column("Icon", DataType.STRING));
		mdi.addColumn(new Column("Label", DataType.STRING));
		mdi.addColumn(new Column("Menu", DataType.STRING));
		mdi.addColumn(new Column("Position", DataType.DOUBLE));
		mdi.addColumn(new Column("SecurityToken", DataType.STRING));
		mdi.addColumn(new Column("MdiTypeKey", DataType.INTEGER));

		Table result;
		try {
			result = viewController.getIndexView(mdi);
		} catch (Exception e) {
			throw new RuntimeException("Error while trying to access xvcasMDI.", e);
		}
		customLogger.logUserRequest("Generating MDI for User " + user);

		if (result.getRows().isEmpty()) {
			throw new RuntimeException("No MDI definition for " + user);
		}

		// Rückgabe nach Position sortieren.
		int position = result.findColumnPosition("Position");

		result.getRows().sort(new Comparator<Row>() {

			@Override
			public int compare(Row r1, Row r2) {
				Double position1 = r1.getValues().get(position).getDoubleValue();
				Double position2 = r2.getValues().get(position).getDoubleValue();
				return position1.compareTo(position2);

			}
		});

		// Ab hier wird die MDI erstellt.

		Main main = new Main();
		Menu mainMenu = new Menu();
		mainMenu.setId("main");
		main.setMenu(mainMenu);

		List<Row> formRows = new ArrayList<>();
		Map<String, Menu> menuMap = new HashMap<>();

		// TODO: Rekursiven Aufruf später.

		for (Row r : mdi.getRows()) {
			int mdiKey = mdi.getValue("MdiTypeKey", r).getIntegerValue();
			if (mdiKey == 1) {
				formRows.add(r);
			} else if (mdiKey == 2) {
				Menu menu = new Menu();
				menu.setId(mdi.getValue("ID", r).getStringValue());
				menu.setText(mdi.getValue("Label", r).getStringValue());

				// Menupunkt an Hauptmenü anhängen.
				mainMenu.getMenuOrEntry().add(menu);
				menuMap.put(menu.getId(), menu);
			} else if (mdiKey == 3) {
				main.setIcon(mdi.getValue("Icon", r).getStringValue());
				main.setTitle(mdi.getValue("Label", r).getStringValue());
			} else {
				throw new IllegalArgumentException("No definition for mdiKey " + mdiKey + "found!");
			}
		}

		// TODO: Filter die Zeilen, die wir zurück bekommen.

		for (Row r : formRows) {
			Action action = new Action();
			action.setAction(mdi.getValue("ID", r).getStringValue() + ".xml");
			action.setId(mdi.getValue("ID", r).getStringValue());
			action.setIcon(mdi.getValue("Icon", r).getStringValue());
			action.setText(mdi.getValue("Label", r).getStringValue());
			main.getAction().add(action);

			Entry entry = new Entry();
			entry.setId(mdi.getValue("ID", r).getStringValue());
			entry.setType("action");
			menuMap.get(entry.getId()).getMenuOrEntry().add(entry);
		}

		return xml2byteArray(main);
	}

	public byte[] xml2byteArray(Main mainXML) {

		try {
			// Create JAXB Context
			JAXBContext jaxbContext = JAXBContext.newInstance(Main.class);
			// Create Marshaller
			Marshaller jaxbMarshaller = jaxbContext.createMarshaller();
			// Required formatting??
			jaxbMarshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, java.lang.Boolean.TRUE);
			jaxbMarshaller.setProperty(Marshaller.JAXB_NO_NAMESPACE_SCHEMA_LOCATION,
					"https://raw.githubusercontent.com/minova-afis/aero.minova.xsd/main/form.xsd");
			jaxbMarshaller.setProperty(Marshaller.JAXB_SCHEMA_LOCATION, "http://www.w3.org/2001/XMLSchema-instance");

			QName qName = new QName("", "form");
			JAXBElement<Main> root = new JAXBElement<>(qName, Main.class, mainXML);
			ByteArrayOutputStream out = new ByteArrayOutputStream();

			jaxbMarshaller.marshal(root, out);
			return out.toByteArray();

		} catch (Exception e) {
			customLogger.logError(e.getMessage(), e);
		}

		return null;

	}
}