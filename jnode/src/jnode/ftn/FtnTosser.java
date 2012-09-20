package jnode.ftn;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.sql.SQLException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import com.j256.ormlite.stmt.UpdateBuilder;

import jnode.dto.Dupe;
import jnode.dto.Echoarea;
import jnode.dto.Echomail;
import jnode.dto.Link;
import jnode.dto.Netmail;
import jnode.dto.Readsign;
import jnode.dto.Rewrite;
import jnode.dto.Robot;
import jnode.dto.Route;
import jnode.dto.Subscription;
import jnode.logger.Logger;
import jnode.main.Main;
import jnode.main.Poll;
import jnode.ndl.FtnNdlAddress;
import jnode.ndl.FtnNdlAddress.Status;
import jnode.ndl.NodelistScanner;
import jnode.orm.ORMManager;
import jnode.protocol.io.Message;
import jnode.robot.IRobot;

/**
 * 
 * @author kreon
 * 
 */
public class FtnTosser {
	static Charset cp866 = Charset.forName("CP866");
	private final static String SEEN_BY = "SEEN-BY:";
	private final static String PATH = "\001PATH:";
	private final static String ROUTE_VIA = "\001Via %s "
			+ Main.info.getVersion() + " %s";
	private final static DateFormat format = new SimpleDateFormat(
			"EEE, dd MMM yyyy HH:mm:ss Z", Locale.US);
	private static final Logger logger = Logger.getLogger(FtnTosser.class);

	/**
	 * Сортировщик 2D-адресов
	 * 
	 * @author kreon
	 * 
	 */
	public static class Ftn2DComparator implements Comparator<Ftn2D> {

		@Override
		public int compare(Ftn2D o1, Ftn2D o2) {
			if (o1.getNet() == o2.getNet()) {
				return o1.getNode() - o2.getNode();
			} else {
				return o1.getNet() - o2.getNet();
			}
		}
	}

	public static String generate8d() {
		byte[] digest = new byte[4];
		for (int i = 0; i < 4; i++) {
			long a = Math.round(Integer.MAX_VALUE * Math.random());
			long b = Math.round(Integer.MIN_VALUE * Math.random());
			long c = a ^ b;
			byte d = (byte) ((c >> 12) & 0xff);
			digest[i] = d;
		}
		return String.format("%02x%02x%02x%02x", digest[0], digest[1],
				digest[2], digest[3]);
	}

	/**
	 * Big-Endian -> Little-Endian
	 * 
	 * @param v
	 * @return
	 */
	public static short revShort(short v) {
		return (short) ((short) ((short) (v >> 8) & 0xff) | (short) (v << 8));
	}

	/**
	 * Подстрока в виде байтов и в cp866
	 * 
	 * @param s
	 * @param len
	 * @return
	 */
	public static byte[] substr(String s, int len) {
		byte[] bytes = s.getBytes(cp866);

		if (bytes.length > len) {
			return ByteBuffer.wrap(bytes, 0, len).array();
		} else {
			return bytes;
		}
	}

	/**
	 * Читаем пакет пока не встретим \0
	 * 
	 * @param is
	 * @return
	 */
	public static String readUntillNull(InputStream is) {
		ByteArrayOutputStream bos = new ByteArrayOutputStream(1);
		int b;
		try {
			while ((b = is.read()) != 0) {
				bos.write(b);
			}
		} catch (IOException e) {
			//
		}
		return new String(bos.toByteArray(), cp866);
	}

	/**
	 * Превращает строки синбаев в лист 2D адресов
	 * 
	 * @param seenByLines
	 * @return
	 */
	public static List<Ftn2D> readSeenBy(String seenByLines) {
		List<Ftn2D> seen = new ArrayList<Ftn2D>();
		String[] seenBy = seenByLines.split("[ \n]");
		int net = 0;
		for (String parts : seenBy) {
			if (parts == null || parts.length() < 1 || parts.equals(SEEN_BY)) {
				continue;
			} else {
				String[] part = parts.split("/");
				int node;
				if (part.length == 2) {
					net = Integer.valueOf(part[0]);
					node = Integer.valueOf(part[1]);
				} else {
					node = Integer.valueOf(part[0]);
				}
				seen.add(new Ftn2D(net, node));
			}
		}
		return seen;
	}

	/**
	 * Превращает лист синбаев в строку для добавления в письмо
	 * 
	 * @param seenby
	 * @return
	 */
	public static String writeSeenBy(List<Ftn2D> seenby) {
		StringBuilder ret = new StringBuilder();
		Collections.sort(seenby, new Ftn2DComparator());
		int net = 0;
		int linelen = 0;
		for (Ftn2D ftn : seenby) {
			if (linelen >= 72) {
				linelen = 0;
				net = 0;
				ret.append("\n");
			}
			if (linelen == 0) {
				ret.append(SEEN_BY);
				linelen += SEEN_BY.length();
			}
			if (net != ftn.getNet()) {
				net = ftn.getNet();
				String app = String.format(" %d/%d", ftn.getNet(),
						ftn.getNode());
				ret.append(app);
				linelen += app.length();
			} else {
				String app = String.format(" %d", ftn.getNode());
				ret.append(app);
				linelen += app.length();
			}
		}
		if (ret.charAt(ret.length() - 1) != '\n') {
			ret.append('\n');
		}
		return ret.toString();
	}

	/**
	 * Превращает путь в List
	 * 
	 * @param seenByLines
	 * @return
	 */
	public static List<Ftn2D> readPath(String seenByLines) {
		List<Ftn2D> seen = new ArrayList<Ftn2D>();
		String[] seenBy = seenByLines.split("[ \n]");
		int net = 0;
		for (String parts : seenBy) {
			if (parts == null || parts.length() < 1 || parts.equals(PATH)) {
				continue;
			} else {
				String[] part = parts.split("/");
				int node;
				if (part.length == 2) {
					net = Integer.valueOf(part[0]);
					node = Integer.valueOf(part[1]);
				} else {
					node = Integer.valueOf(part[0]);
				}
				seen.add(new Ftn2D(net, node));
			}
		}
		return seen;
	}

	/**
	 * Превращает List в путь
	 * 
	 * @param path
	 * @return
	 */
	public static String writePath(List<Ftn2D> path) {
		StringBuilder ret = new StringBuilder();
		int net = 0;
		int linelen = 0;
		for (Ftn2D ftn : path) {
			if (linelen >= 72) {
				linelen = 0;
				net = 0;
				ret.append("\n");
			}
			if (linelen == 0) {
				ret.append(PATH);
				linelen += PATH.length();
			}
			if (net != ftn.getNet()) {
				net = ftn.getNet();
				String app = String.format(" %d/%d", ftn.getNet(),
						ftn.getNode());
				ret.append(app);
				linelen += app.length();
			} else {
				String app = String.format(" %d", ftn.getNode());
				ret.append(app);
				linelen += app.length();
			}
		}
		if (ret.charAt(ret.length() - 1) != '\n') {
			ret.append('\n');
		}
		return ret.toString();
	}

	/**
	 * Читаем 2d-адреса через разделитель
	 * 
	 * @param list2d
	 * @return
	 */
	private static List<Ftn2D> read2D(String list2d) {
		List<Ftn2D> ret = new ArrayList<Ftn2D>();
		for (String l2d : list2d.split(" ")) {
			String[] part = l2d.split("/");
			try {
				ret.add(new Ftn2D(Integer.valueOf(part[0]), Integer
						.valueOf(part[1])));
			} catch (RuntimeException e) {
				logger.warn("Неправильный 2D-адрес: " + list2d);
			}
		}
		return ret;
	}

	/**
	 * Пишем 2d-адреса через разделитель
	 * 
	 * @param list
	 * @param sort
	 * @return
	 */
	private static String write2D(List<Ftn2D> list, boolean sort) {
		StringBuilder ret = new StringBuilder();
		if (sort) {
			Collections.sort(list, new Ftn2DComparator());
		}
		boolean flag = false;
		for (Ftn2D d : list) {
			if (flag) {
				ret.append(" ");
			} else {
				flag = true;
			}
			ret.append(String.format("%d/%d", d.getNet(), d.getNode()));
		}
		return ret.toString();
	}

	/**
	 * Конвертер
	 * 
	 * @param mail
	 * @return
	 */
	private static FtnMessage echomailToFtnMessage(Echomail mail) {
		FtnMessage message = new FtnMessage();
		message.setFromName(mail.getFromName());
		message.setToName(mail.getToName());
		message.setFromAddr(new FtnAddress(mail.getFromFTN()));
		message.setArea(mail.getArea().getName().toUpperCase());
		message.setDate(mail.getDate());
		message.setSubject(mail.getSubject());
		message.setText(mail.getText());
		message.setSeenby(read2D(mail.getSeenBy()));
		message.setPath(read2D(mail.getPath()));
		return message;
	}

	/**
	 * Конвертер
	 * 
	 * @param mail
	 * @return
	 */
	private static FtnMessage netmailToFtnMessage(Netmail mail) {
		// update fields
		FtnMessage message = new FtnMessage();
		message.setFromName(mail.getFromName());
		message.setToName(mail.getToName());
		message.setFromAddr(new FtnAddress(mail.getFromFTN()));
		message.setToAddr(new FtnAddress(mail.getToFTN()));
		message.setDate(mail.getDate());
		message.setSubject(mail.getSubject());
		StringBuilder text = new StringBuilder();
		text.append(mail.getText());
		if (text.charAt(text.length() - 1) != '\n') {
			text.append('\n');
		}
		text.append(String.format(ROUTE_VIA, Main.info.getAddress().toString(),
				format.format(new Date())));
		message.setText(text.toString());
		return message;
	}

	/**
	 * Распаковка из зип-архива
	 * 
	 * @param message
	 * @return
	 */
	private static FtnPkt[] unpack(Message message) {
		ArrayList<FtnPkt> unzipped = new ArrayList<FtnPkt>();
		String filename = message.getMessageName().toLowerCase();
		if (filename.matches("^[a-f0-9]{8}\\.pkt$")) {
			FtnPkt pkt = new FtnPkt();
			pkt.unpack(message.getInputStream());
			unzipped.add(pkt);
		} else if (filename.matches("^[a-f0-9]{8}\\.[a-z0-9][a-z0-9][a-z0-9]$")) {
			try {
				ZipInputStream zis = new ZipInputStream(
						message.getInputStream());
				while (zis.getNextEntry() != null) {
					FtnPkt pkt = new FtnPkt();
					pkt.unpack(zis, false);
					unzipped.add(pkt);
				}
			} catch (IOException e) {
				logger.error("Не удалось распаковать " + filename);
			}
		} else {
			filename = filename.replaceAll("^[\\./\\\\]*", "_");
			File file = new File(Main.getInbound() + File.separator + filename);
			try {
				FileOutputStream fos = new FileOutputStream(file);
				while (message.getInputStream().available() > 0) {
					byte[] block = new byte[1024];
					int len = message.getInputStream().read(block);
					fos.write(block, 0, len);
				}
				fos.close();
				logger.info("Получен файл " + file.getAbsolutePath() + " ("
						+ file.length() + ")");
			} catch (IOException e) {
				logger.error("Не удалось записать файл " + filename + ": "
						+ e.getMessage());
			}
		}
		return unzipped.toArray(new FtnPkt[0]);
	}

	/**
	 * Проверка соответствия маски :)
	 * 
	 * @param route
	 * @param message
	 * @return
	 */
	private static boolean completeMask(Route route, FtnMessage message) {
		boolean ok = true;
		String[] regexp = new String[] { route.getFromAddr(),
				route.getToAddr(), route.getFromName(), route.getToName(),
				route.getSubject() };
		String[] check = new String[] { message.getFromAddr().toString(),
				message.getToAddr().toString(), message.getFromName(),
				message.getToName(), message.getSubject() };
		for (int i = 0; i < 5; i++) {
			if (regexp[i] != null && !regexp[i].equals("*")) {
				logger.debug("Проверяем " + check[i] + " на соответствие "
						+ regexp[i]);
				if (check[i] == null || !check[i].matches(regexp[i])) {
					ok = false;
				}
			}
		}
		return ok;
	}

	/**
	 * Проверка соответствия маски :)
	 * 
	 * @param rewrite
	 * @param message
	 * @return
	 */
	private static boolean completeMask(Rewrite rewrite, FtnMessage message) {
		boolean ok = true;
		String[] regexp = new String[] { rewrite.getOrig_from_addr(),
				rewrite.getOrig_to_addr(), rewrite.getOrig_from_name(),
				rewrite.getOrig_to_name(), rewrite.getOrig_subject() };
		String[] check = new String[] { message.getFromAddr().toString(),
				message.getToAddr().toString(), message.getFromName(),
				message.getToName(), message.getSubject() };
		for (int i = 0; i < 5; i++) {
			if (regexp[i] != null && !regexp[i].equals("*")) {
				logger.debug("Проверяем " + check[i] + " на соответствие "
						+ regexp[i]);
				if (check[i] == null || !check[i].matches(regexp[i])) {
					ok = false;
				}
			}
		}
		return ok;
	}

	/**
	 * Перезапись сообщения
	 * 
	 * @param rewrite
	 * @param message
	 * @return
	 */
	private static void rewrite(Rewrite rewrite, FtnMessage message) {
		String[] fields = new String[] { rewrite.getNew_from_addr(),
				rewrite.getNew_to_addr(), rewrite.getNew_from_name(),
				rewrite.getNew_to_name(), rewrite.getNew_subject() };
		for (int i = 0; i < 5; i++) {
			if (fields[i] != null && !fields[i].equals("*")) {
				switch (i) {
				case 0:
					FtnAddress nfa = new FtnAddress(fields[i]);
					Matcher msgid = Pattern
							.compile(
									"^\001MSGID: " + message.getFromAddr()
											+ " (\\S+)$", Pattern.MULTILINE)
							.matcher(message.getText());
					if (msgid.find()) {
						message.setText(msgid.replaceFirst("\001MSGID: " + nfa
								+ " $1"));
					}
					Matcher origin = Pattern.compile(
							"^ \\* Origin: (.*) \\(" + message.getFromAddr()
									+ "\\)$", Pattern.MULTILINE).matcher(
							message.getText());
					if (origin.find()) {
						message.setText(origin.replaceFirst(" * Origin: $1 ("
								+ nfa + ")"));
					}
					message.setFromAddr(nfa);
					logger.info("Перезаписываем fromAddr на " + fields[i]);
					break;
				case 1:
					message.setToAddr(new FtnAddress(fields[i]));
					logger.info("Перезаписываем toAddr на " + fields[i]);
					break;
				case 2:
					message.setFromName(fields[i]);
					logger.info("Перезаписываем fromAddr на " + fields[i]);
					break;
				case 3:
					message.setToName(fields[i]);
					logger.info("Перезаписываем fromAddr на " + fields[i]);
					break;
				case 4:
					message.setSubject(fields[i]);
					logger.info("Перезаписываем fromAddr на " + fields[i]);
					break;
				}
			}
		}
	}

	/**
	 * Проверям сообщение на соответствие роботу
	 * 
	 * @param message
	 * @return
	 */
	private static boolean checkRobot(FtnMessage message) {
		boolean isRobot = false;
		String robotname = "";
		if (message.isNetmail()) {
			if (message.getToAddr().equals(Main.info.getAddress())) {
				try {
					Robot robot = ORMManager.robot().queryForId(
							message.getToName().toLowerCase());
					if (robot != null) {
						robotname = robot.getRobot();
						isRobot = true;
						Class<?> clazz = Class.forName(robot.getClassName());
						IRobot irobot = (IRobot) clazz.newInstance();
						logger.info("Сообщение " + message.getMsgid()
								+ " передано роботу " + robotname);
						irobot.execute(message);
					}
				} catch (SQLException e) {
					logger.error("Ошибка при получении робота");
				} catch (ClassNotFoundException e) {
					logger.error("Ошибка при инициализации робота " + robotname);
					e.printStackTrace();
				} catch (Exception e) {
					logger.error("Ошибка при обработке сообщения робота "
							+ robotname);
					e.printStackTrace();
				}
			}
		}
		return isRobot;
	}

	/**
	 * Получаем роутинг для нетмейла
	 * 
	 * @param message
	 * @return
	 */
	public static Link getRouting(FtnMessage message) {
		Link routeVia = null;
		FtnAddress routeTo = new FtnAddress(message.getToAddr().toString());
		{
			try {
				List<Link> lnk = ORMManager.link().queryForEq("ftn_address",
						routeTo.toString());
				if (lnk.isEmpty()) {
					if (routeTo.getPoint() > 0) {
						routeTo.setPoint(0);
						lnk = ORMManager.link().queryForEq("ftn_address",
								routeTo.toString());
						if (!lnk.isEmpty()) {
							routeVia = lnk.get(0);
						}
					}
				} else {
					routeVia = lnk.get(0);
				}
			} catch (SQLException e) {
				logger.error("Ошибка при поиска routeVia", e);
			}
		}
		if (routeVia == null) {
			try {
				List<Route> routes = ORMManager.route().queryBuilder()
						.orderBy("nice", true).query();
				for (Route route : routes) {
					if (completeMask(route, message)) {
						routeVia = route.getRouteVia();
						break;
					}
				}
			} catch (SQLException e) {
				logger.error("Ошибка при получении роутинга", e);
			}
		}
		return routeVia;
	}

	/**
	 * Получаем сообщения из бандлов
	 * 
	 * @param connector
	 */
	public static void tossIncoming(Message message, Link link) {
		if (message == null) {
			return;
		}
		List<Link> pollAfterEnd = new ArrayList<Link>();
		Map<String, Integer> tossed = new HashMap<String, Integer>();
		Map<String, Integer> bad = new HashMap<String, Integer>();
		FtnPkt[] pkts = unpack(message);
		for (FtnPkt pkt : pkts) {
			if (message.isSecure()) {
				if (!link.getPaketPassword()
						.equalsIgnoreCase(pkt.getPassword())) {
					logger.warn("Пароль для пакета не совпал - пакет уничтожен");
					continue;
				}
			}
			for (FtnMessage ftnm : pkt.getMessages()) {
				if (message.isSecure()) {
					if (checkRobot(ftnm)) {
						continue;
					}
				}
				if (ftnm.isNetmail()) {
					// проверить from и to
					FtnNdlAddress from = NodelistScanner.getInstance()
							.isExists(ftnm.getFromAddr());
					FtnNdlAddress to = NodelistScanner.getInstance().isExists(
							ftnm.getToAddr());
					if (from == null) {
						logger.warn(String
								.format("Netmail %s -> %s уничтожен ( отправитель не найден в нодлисте )",
										ftnm.getFromAddr().toString(), ftnm
												.getToAddr().toString()));
						continue;
					} else if (to == null) {
						try {
							writeReply(
									ftnm,
									"Destination not found",
									"Sorry, but destination of your netmail is not found in nodelist\nMessage rejected");
						} catch (SQLException e) {
							logger.error("Не удалось написать сообщение в ответ");
						}
						logger.warn(String
								.format("Netmail %s -> %s уничтожен ( получатель не найден в нодлисте )",
										ftnm.getFromAddr().toString(), ftnm
												.getToAddr().toString()));
						continue;
					} else if (to.getStatus().equals(Status.DOWN)) {
						try {
							writeReply(ftnm, "Destination is DOWN",
									"Sorry, but destination of your netmail is DOWN\nMessage rejected");
						} catch (SQLException e) {
							logger.error("Не удалось написать сообщение в ответ");
						}
						logger.warn(String
								.format("Netmail %s -> %s уничтожен ( получатель имеет статус Down )",
										ftnm.getFromAddr().toString(), ftnm
												.getToAddr().toString()));
						continue;
					}
					try {
						List<Rewrite> rewrites = ORMManager.rewrite()
								.queryBuilder().orderBy("nice", true).where()
								.eq("type", (Rewrite.Type.NETMAIL)).query();
						for (Rewrite rewrite : rewrites) {
							if (completeMask(rewrite, ftnm)) {
								logger.info("(N) Найдено соответствие, переписываем сообщение "
										+ ftnm.getMsgid());
								rewrite(rewrite, ftnm);
								if (rewrite.isLast()) {
									break;
								}
							}
						}
					} catch (SQLException e1) {
						logger.warn("Не удалось получить rewrite", e1);
					}
					Link routeVia = getRouting(ftnm);
					if (routeVia == null) {
						Integer n = bad.get("netmail");
						bad.put("netmail", (n == null) ? 1 : n + 1);
						logger.warn(String
								.format("Netmail %s -> %s уничтожен ( не найден роутинг )",
										ftnm.getFromAddr().toString(), ftnm
												.getToAddr().toString()));
					} else {
						try {
							Netmail netmail = new Netmail();
							netmail.setRouteVia(routeVia);
							netmail.setDate(ftnm.getDate());
							netmail.setFromFTN(ftnm.getFromAddr().toString());
							netmail.setToFTN(ftnm.getToAddr().toString());
							netmail.setFromName(ftnm.getFromName());
							netmail.setToName(ftnm.getToName());
							netmail.setSubject(ftnm.getSubject());
							netmail.setText(ftnm.getText());
							ORMManager.netmail().create(netmail);
							Integer n = tossed.get("netmail");
							tossed.put("netmail", (n == null) ? 1 : n + 1);
							routeVia = ORMManager.link().queryForSameId(
									routeVia);
							logger.info(String
									.format("Netmail %s -> %s будет отправлен через %s",
											ftnm.getFromAddr().toString(), ftnm
													.getToAddr().toString(),
											routeVia.getLinkAddress()));
							pollAfterEnd.add(routeVia);
						} catch (SQLException e) {
							e.printStackTrace();
							logger.error("Ошибка при сохранении нетмейла", e);
						}
					}
				} else if (message.isSecure()) {
					try {
						Echoarea area;
						Subscription sub;
						boolean flag = true;
						{
							List<Echoarea> areas = ORMManager.echoarea()
									.queryForEq("name",
											ftnm.getArea().toLowerCase());
							if (areas.isEmpty()) {
								// TODO: autoCreate
								area = new Echoarea();
								area.setName(ftnm.getArea().toLowerCase());
								area.setDescription("Autocreated echoarea");
								ORMManager.echoarea().create(area);
								sub = new Subscription();
								sub.setArea(area);
								sub.setLink(link);
								sub.setLast(0L);
								ORMManager.subscription().create(sub);
							} else {
								area = areas.get(0);
								List<Subscription> subs = ORMManager
										.subscription().queryBuilder().where()
										.eq("echoarea_id", area.getId()).and()
										.eq("link_id", link.getId()).query();
								if (!subs.isEmpty()) {
									sub = subs.get(0);
								} else {
									flag = false;
								}
							}
						}
						if (flag) {
							try {
								if (!ORMManager.dupe().queryBuilder().where()
										.eq("msgid", ftnm.getMsgid()).and()
										.eq("echoarea_id", area).query()
										.isEmpty()) {
									logger.warn(ftnm.getMsgid()
											+ " дюп - уничтожен");
									Integer n = bad.get(ftnm.getArea());
									bad.put(ftnm.getArea(), (n == null) ? 1
											: n + 1);
								}
							} catch (SQLException e) {
								logger.warn(
										"Не удалось проверить "
												+ ftnm.getMsgid() + " на дюпы",
										e);
							}
							try {
								List<Rewrite> rewrites = ORMManager.rewrite()
										.queryBuilder().orderBy("nice", true)
										.where()
										.eq("type", Rewrite.Type.ECHOMAIL)
										.query();
								for (Rewrite rewrite : rewrites) {
									if (completeMask(rewrite, ftnm)) {
										logger.info("(E) Найдено соответствие, переписываем сообщение "
												+ ftnm.getMsgid());
										rewrite(rewrite, ftnm);
										if (rewrite.isLast()) {
											break;
										}
									}
								}
							} catch (SQLException e1) {
								e1.printStackTrace();
								logger.warn("Не удалось получить rewrite", e1);
							}
							Echomail mail = new Echomail();
							mail.setArea(area);
							mail.setDate(ftnm.getDate());
							mail.setFromFTN(ftnm.getFromAddr().toString());
							mail.setFromName(ftnm.getFromName());
							mail.setToName(ftnm.getToName());
							mail.setSubject(ftnm.getSubject());
							mail.setText(ftnm.getText());
							mail.setSeenBy(write2D(ftnm.getSeenby(), true));
							mail.setPath(write2D(ftnm.getPath(), false));
							ORMManager.echomail().create(mail);
							try {
								Dupe dupe = new Dupe();
								dupe.setEchoarea(area);
								dupe.setMsgid(ftnm.getMsgid());
								ORMManager.dupe().create(dupe);
							} catch (SQLException e1) {
								logger.warn(
										"Не удалось записать "
												+ ftnm.getMsgid()
												+ " на в базу дюпов", e1);
							}
							// метка что уже прочитано
							Readsign sign = new Readsign();
							sign.setLink(link);
							sign.setMail(mail);
							ORMManager.readsign().create(sign);
							Integer n = tossed.get(ftnm.getArea());
							tossed.put(ftnm.getArea(), (n == null) ? 1 : n + 1);
						} else {
							Integer n = bad.get(ftnm.getArea());
							bad.put(ftnm.getArea(), (n == null) ? 1 : n + 1);
						}
					} catch (SQLException e) {
						logger.error(
								"Не удалось записать сообщение "
										+ ftnm.getMsgid(), e);
						Integer n = bad.get(ftnm.getArea());
						bad.put(ftnm.getArea(), (n == null) ? 1 : n + 1);
					}
				} else {
					logger.info("Эхомейл по unsecure-соединению - уничтожен");
				}
			}
		}
		if (!tossed.isEmpty()) {
			logger.info("Записано сообщений:");
			for (String area : tossed.keySet()) {
				logger.info(String.format("\t%s - %d", area, tossed.get(area)));
			}
		}
		if (!bad.isEmpty()) {
			logger.warn("Уничтожено сообщений:");
			for (String area : bad.keySet()) {
				logger.warn(String.format("\t%s - %d", area, bad.get(area)));
			}
		}
		if (!pollAfterEnd.isEmpty()) {
			for (Link l : pollAfterEnd) {
				if (!"".equals(l.getProtocolHost()) && l.getProtocolPort() > 0) {
					new Poll(l).start();
				}
			}
		}
	}

	public static void writeReply(FtnMessage fmsg, String subject, String text)
			throws SQLException {

		Netmail netmail = new Netmail();
		netmail.setFromFTN(Main.info.getAddress().toString());
		netmail.setFromName(Main.info.getStationName());
		netmail.setToFTN(fmsg.getFromAddr().toString());
		netmail.setToName(fmsg.getFromName());
		netmail.setSubject(subject);
		netmail.setDate(new Date());
		StringBuilder sb = new StringBuilder();
		sb.append(String
				.format("\001REPLY: %s\n\001MSGID: %s %s\n\001PID: %s\001TID: %s\nHello, %s!\n\n",
						fmsg.getMsgid(), Main.info.getAddress().toString(),
						FtnTosser.generate8d(), Main.info.getVersion(),
						Main.info.getVersion(), netmail.getToName()));
		sb.append(text);
		sb.append("\n\n========== Original message ==========\n");
		sb.append(fmsg.getText().replaceAll("\001", "@"));
		sb.append("========== Original message ==========\n\n--- "
				+ Main.info.getVersion() + "\n");
		netmail.setText(sb.toString());
		FtnMessage ret = new FtnMessage();
		ret.setFromAddr(new FtnAddress(Main.info.getAddress().toString()));
		ret.setToAddr(fmsg.getFromAddr());
		Link routeVia = FtnTosser.getRouting(ret);
		if (routeVia == null) {
			logger.error("Не могу найти роутинг для ответа на сообщение"
					+ fmsg.getMsgid());
			return;
		}
		netmail.setRouteVia(routeVia);
		ORMManager.netmail().create(netmail);
		logger.info("Создан Netmail #" + netmail.getId());
	}

	/**
	 * Получить новые сообщения для линка
	 * 
	 * @param link
	 * @return
	 */
	public static List<Message> getMessagesForLink(Link link) {
		List<Message> packedMessages = new ArrayList<Message>();
		try {
			FtnAddress link_address = new FtnAddress(link.getLinkAddress());
			FtnAddress our_address = Main.info.getAddress();
			Ftn2D link2d = new Ftn2D(link_address.getNet(),
					link_address.getNode());
			Ftn2D our2d = new Ftn2D(our_address.getNet(), our_address.getNode());
			try {
				List<FtnMessage> messages = new ArrayList<FtnMessage>();
				List<Subscription> subscr = ORMManager.subscription()
						.queryForEq("link_id", link.getId());
				for (Subscription sub : subscr) {
					List<Echomail> newmail = ORMManager.echomail()
							.queryBuilder().orderBy("id", true).where()
							.eq("echoarea_id", sub.getArea().getId()).and()
							.gt("id", sub.getLast()).query();
					for (Echomail mail : newmail) {
						if (mail.getId() > sub.getLast()) {
							sub.setLast(mail.getId());
						}
						List<Readsign> signs = ORMManager.readsign()
								.queryBuilder().where()
								.eq("link_id", link.getId()).and()
								.eq("echomail_id", mail.getId()).query();
						if (signs.isEmpty()) {
							Set<Ftn2D> seenby = new HashSet<Ftn2D>(
									FtnTosser.read2D(mail.getSeenBy()));
							/**
							 * Если мы пакуем на линка - то чекаем синбаи
							 */
							if (seenby.contains(link2d)
									&& link_address.getPoint() == 0) {
								logger.info(our2d + " есть в синбаях для "
										+ link_address);
							} else {
								seenby.add(our2d);
								seenby.add(link2d);
								for (Subscription s : ORMManager.subscription()
										.queryForEq("echoarea_id",
												mail.getArea().getId())) {
									Link l = ORMManager.link().queryForSameId(
											s.getLink());
									FtnAddress addr = new FtnAddress(
											l.getLinkAddress());
									Ftn2D d2 = new Ftn2D(addr.getNet(),
											addr.getNode());
									seenby.add(d2);
								}

								List<Ftn2D> path = FtnTosser.read2D(mail
										.getPath());
								if (!path.contains(our2d)) {
									path.add(our2d);
								}
								mail.setPath(FtnTosser.write2D(path, false));
								mail.setSeenBy(FtnTosser.write2D(
										new ArrayList<Ftn2D>(seenby), true));
								// не надо писать новые синбаи!! транзитная
								// почта
								// будет дюпится
								// ORMManager.echomail().update(mail);
								logger.info("Пакуем сообщение #" + mail.getId()
										+ " (" + mail.getArea().getName()
										+ ") для " + link.getLinkAddress());
								messages.add(FtnTosser
										.echomailToFtnMessage(mail));
							}
							Readsign sign = new Readsign();
							sign.setLink(link);
							sign.setMail(mail);
							ORMManager.readsign().create(sign);
						}
					}
					{
						UpdateBuilder<Subscription, ?> upd = ORMManager
								.subscription().updateBuilder();
						upd.updateColumnValue("lastmessageid", sub.getLast());
						upd.where().eq("link_id", sub.getLink()).and()
								.eq("echoarea_id", sub.getArea());
						ORMManager.subscription().update(upd.prepare());
					}
				}
				if (!messages.isEmpty()) {
					ByteArrayOutputStream out = new ByteArrayOutputStream();
					ZipOutputStream zos = new ZipOutputStream(out);
					zos.setMethod(ZipOutputStream.DEFLATED);
					FtnPkt pkt = new FtnPkt(our_address, link_address,
							link.getPaketPassword(), new Date());
					for (FtnMessage msg : messages) {
						pkt.getMessages().add(msg);
					}
					byte[] packet = pkt.pack();
					ZipEntry ze = new ZipEntry(String.format("%s.pkt",
							generate8d()));
					ze.setMethod(ZipEntry.DEFLATED);
					ze.setSize(packet.length);
					CRC32 crc32 = new CRC32();
					crc32.update(packet);
					ze.setCrc(crc32.getValue());
					zos.putNextEntry(ze);
					zos.write(packet);
					zos.close();
					byte[] zip = out.toByteArray();
					Message message = new Message(String.format("%s.fr0",
							generate8d()), zip.length);
					message.setInputStream(new ByteArrayInputStream(zip));
					packedMessages.add(message);
				}
			} catch (Exception e) {
				e.printStackTrace();
				logger.error(
						"Ошибка обработки echomail для "
								+ link.getLinkAddress(), e);
			}
			// netmail
			try {
				ByteArrayOutputStream bos = new ByteArrayOutputStream();
				FtnPkt pkt = new FtnPkt(our_address, link_address,
						link.getPaketPassword(), new Date());
				List<Netmail> netmails = ORMManager.netmail().queryForEq(
						"route_via", link.getId());
				if (!netmails.isEmpty()) {
					for (Netmail netmail : netmails) {
						FtnMessage msg = netmailToFtnMessage(netmail);
						pkt.getMessages().add(msg);
						logger.debug(String.format(
								"Пакуем netmail #%d %s -> %s для %s",
								netmail.getId(), netmail.getFromFTN(),
								netmail.getToFTN(), link.getLinkAddress()));
						ORMManager.netmail().delete(netmail);
					}
					bos.write(pkt.pack());
					byte[] netmail = bos.toByteArray();
					bos.close();
					Message message = new Message(String.format("%s.pkt",
							generate8d()), netmail.length);
					message.setInputStream(new ByteArrayInputStream(netmail));
					packedMessages.add(message);
				}
			} catch (Exception e) {
				e.printStackTrace();
				logger.error(
						"Ошибка обработки netmail для " + link.getLinkAddress(),
						e);
			}
		} catch (Exception e) {
			logger.error("Ошибка получения сообщений для "
					+ link.getLinkAddress());
		}
		return packedMessages;
	}

}
