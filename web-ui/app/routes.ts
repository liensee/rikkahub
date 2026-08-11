import { type RouteConfig, index, route } from "@react-router/dev/routes";

export default [
  index("routes/home.tsx"),
  route("c/:id", "routes/c.$id.tsx"),
  route("workflows", "routes/workflows.tsx"),
  route("workflows/:id", "routes/workflows.$id.tsx"),
] satisfies RouteConfig;
