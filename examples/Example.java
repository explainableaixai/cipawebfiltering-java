import com.alphaquantum.cipawebfiltering.CipaWebFilteringClient;
import com.alphaquantum.cipawebfiltering.Classification;
import com.alphaquantum.cipawebfiltering.Policy;
import java.util.List;

public class Example {
  public static void main(String[] args) throws Exception {
    CipaWebFilteringClient client = new CipaWebFilteringClient(System.getenv("CWF_API_KEY"));
    Policy school = Policy.withCircumvention();
    for (Classification r : client.classifyMany(List.of("khanacademy.org", "example.com"))) {
      System.out.println(r.domain() + " " + r.categories() + " -> " + (school.blocks(r.categories()) ? "block" : "allow"));
    }
  }
}
